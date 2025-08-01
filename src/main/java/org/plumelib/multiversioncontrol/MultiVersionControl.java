package org.plumelib.multiversioncontrol;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.checkerframework.checker.index.qual.GTENegativeOne;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.lock.qual.GuardSatisfied;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.checkerframework.checker.regex.qual.Regex;
import org.checkerframework.common.initializedfields.qual.EnsuresInitializedFields;
import org.checkerframework.common.value.qual.MinLen;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.ini4j.Ini;
import org.ini4j.Profile;
import org.plumelib.options.Option;
import org.plumelib.options.OptionGroup;
import org.plumelib.options.Options;
import org.plumelib.util.EntryReader;
import org.plumelib.util.FilesPlume;
import org.plumelib.util.StringsPlume;
import org.plumelib.util.UtilPlume;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNWCClient;

// A related program is the "mr" program (http://kitenet.net/~joey/code/mr/).
// To read its documentation:  pod2man mr | nroff -man
// Some differences are:
//  * mvc knows how to search for all repositories
//  * mvc uses a timeout
//  * mvc tries to improve tool output:
//     * mvc tries to be as quiet as possible.  The fact that it issues
//       output only if there is a problem makes "mvc status" appropriate
//       for running as a cron job, and reduces distraction.
//     * mvc rewrites paths from relative to absolute form or adds
//       pathnames, to make output comprehensible without knowing the
//       working directory of the command.
//  * mvc's configuration files tend to be smaller & simpler

/**
 * This program simplifies managing your clones/checkouts. This program lets you run a version
 * control command, such as "status" or "pull", on a <b>set</b> of CVS/Git/Hg/SVN clones/checkouts
 * rather than just one. You might want to pull/update all of them, or you might want to know
 * whether any of them have uncommitted changes. When setting up a new account, you might want to
 * clone them all. This program does those tasks.
 *
 * <p>You can specify the set of clones for the program to manage in a file {@code .mvc-checkouts},
 * or you can pass {@code --search} to make the program search your directory structure to find all
 * of your clones. For example (assuming you have a <a href="#installation">{@code mvc} alias</a>),
 * to list all un-committed changed files under your home directory:
 *
 * <pre>
 * mvc status --search=true</pre>
 *
 * <p>This program accepts these arguments:
 *
 * <pre>
 *   clone     -- Clone (check out) all repositories.
 *   checkout  -- Same as clone.
 *   pull      -- Pull and update all clones.
 *   update    -- Same as pull.
 *   status    -- Show files that are changed but not committed, or committed
 *                but not pushed, or have shelved/stashed changes.
 *   list      -- List the clones/checkouts that this program is aware of.
 * </pre>
 *
 * <p>(The {@code commit} action is not supported, because that is not something that should be done
 * in an automated way &mdash; it needs a user-written commit message.)
 *
 * <p><b>Command-line arguments</b>
 *
 * <p>The command-line options are as follows:
 * <!-- start options doc (DO NOT EDIT BY HAND) -->
 *
 * <ul>
 *   <li id="optiongroup:Configuration-file">Configuration file
 *       <ul>
 *         <li id="option:home"><b>--home=</b><i>string</i>. User home directory. [default Java
 *             {@code user.home} property]
 *         <li id="option:checkouts"><b>--checkouts=</b><i>string</i>. File with list of clones. Set
 *             it to /dev/null to suppress reading. [default {@code .mvc-checkouts} in home
 *             directory]
 *       </ul>
 *   <li id="optiongroup:Miscellaneous-options">Miscellaneous options
 *       <ul>
 *         <li id="option:redo-existing"><b>--redo-existing=</b><i>boolean</i>. If false, clone
 *             command skips existing directories. [default: false]
 *         <li id="option:timeout"><b>--timeout=</b><i>int</i>. Terminating the process can leave
 *             the repository in a bad state, so set this rather high for safety. Also, the timeout
 *             needs to account for the time to run hooks (that might recompile or run tests).
 *             [default: 600]
 *       </ul>
 *   <li id="optiongroup:Searching-for-clones">Searching for clones
 *       <ul>
 *         <li id="option:search"><b>--search=</b><i>boolean</i>. If true, search for all clones,
 *             not just those listed in a file. [default: false]
 *         <li id="option:search-prefix"><b>--search-prefix=</b><i>boolean</i>. If true, search for
 *             all clones whose directory is a prefix of one in the cofiguration file. [default:
 *             false]
 *         <li id="option:dir"><b>--dir=</b><i>string</i> {@code [+]}. Directory under which to
 *             search for clones, when using {@code --search} [default home directory]
 *         <li id="option:ignore-dir"><b>--ignore-dir=</b><i>string</i> {@code [+]}. Directories
 *             under which to NOT search for clones. May include leading "~/".
 *       </ul>
 *   <li id="optiongroup:Paths-to-programs">Paths to programs
 *       <ul>
 *         <li id="option:cvs-executable"><b>--cvs-executable=</b><i>string</i>. Path to the cvs
 *             program. [default: cvs]
 *         <li id="option:git-executable"><b>--git-executable=</b><i>string</i>. Path to the git
 *             program. [default: git]
 *         <li id="option:hg-executable"><b>--hg-executable=</b><i>string</i>. Path to the hg
 *             program. [default: hg]
 *         <li id="option:svn-executable"><b>--svn-executable=</b><i>string</i>. Path to the svn
 *             program. [default: svn]
 *         <li id="option:insecure"><b>--insecure=</b><i>boolean</i>. If true, use --insecure when
 *             invoking programs. [default: false]
 *         <li id="option:cvs-arg"><b>--cvs-arg=</b><i>string</i> {@code [+]}. Extra argument to
 *             pass to the cvs program.
 *         <li id="option:git-arg"><b>--git-arg=</b><i>string</i> {@code [+]}. Extra argument to
 *             pass to the git program.
 *         <li id="option:hg-arg"><b>--hg-arg=</b><i>string</i> {@code [+]}. Extra argument to pass
 *             to the hg program.
 *         <li id="option:svn-arg"><b>--svn-arg=</b><i>string</i> {@code [+]}. Extra argument to
 *             pass to the svn program.
 *       </ul>
 *   <li id="optiongroup:Diagnostics">Diagnostics
 *       <ul>
 *         <li id="option:show"><b>--show=</b><i>boolean</i>. If true, display each command is it is
 *             executed. [default: false]
 *         <li id="option:print-directory"><b>--print-directory=</b><i>boolean</i>. If true, print
 *             the directory before executing commands in it. [default: false]
 *         <li id="option:dry-run"><b>--dry-run=</b><i>boolean</i>. Perform a "dry run": print
 *             commands but do not execute them. [default: false]
 *         <li id="option:quiet"><b>-q</b> <b>--quiet=</b><i>boolean</i>. If true, run quietly
 *             (e.g., no output about missing directories). [default: true]
 *         <li id="option:debug"><b>--debug=</b><i>boolean</i>. Print debugging output. [default:
 *             false]
 *         <li id="option:debug-replacers"><b>--debug-replacers=</b><i>boolean</i>. Debug
 *             'replacers' that filter command output. [default: false]
 *         <li id="option:debug-process-output"><b>--debug-process-output=</b><i>boolean</i>.
 *             Lightweight debugging of 'replacers' that filter command output. [default: false]
 *       </ul>
 * </ul>
 *
 * {@code [+]} means option can be specified multiple times
 * <!-- end options doc -->
 *
 * <p><b>File format for {@code .mvc-checkouts} file</b>
 *
 * <p>The remainder of this document describes the file format for the {@code .mvc-checkouts} file.
 *
 * <p>(Note: because mvc can search for all checkouts in your directory, you don't need a {@code
 * .mvc-checkouts} file. Using a {@code .mvc-checkouts} file makes the program faster because it
 * does not have to search all of your directories. It also permits you to process only a certain
 * set of checkouts.)
 *
 * <p>The {@code .mvc-checkouts} file contains a list of <em>sections</em>. Each section names
 * either a root from which a sub-part (e.g., a module or a subdirectory) will be checked out, or a
 * repository all of which will be checked out. Examples include:
 *
 * <pre>
 * CVSROOT: :ext:login.csail.mit.edu:/afs/csail.mit.edu/u/m/mernst/.CVS/.CVS-mernst
 * SVNROOT: svn+ssh://tricycle.cs.washington.edu/cse/courses/cse403/09sp
 * SVNREPOS: svn+ssh://login.csail.mit.edu/afs/csail/u/a/user/.SVN/papers/parameterize-paper/trunk
 * HGREPOS: https://jsr308-langtools.googlecode.com/hg</pre>
 *
 * <p>Within each section is a list of directories that contain a checkout from that repository. If
 * the section names a root, then a module or subdirectory is needed. By default, the directory's
 * basename is used. This can be overridden by specifying the module/subdirectory on the same line,
 * after a space. If the section names a repository, then no module information is needed or used.
 *
 * <p>When performing a checkout, the parent directories are created if needed.
 *
 * <p>In the file, blank lines, and lines beginning with "#", are ignored.
 *
 * <p>Here are some example sections:
 *
 * <pre>
 * CVSROOT: :ext:login.csail.mit.edu:/afs/csail.mit.edu/group/pag/projects/classify-tests/.CVS
 * ~/research/testing/symstra-eclat-paper
 * ~/research/testing/symstra-eclat-code
 * ~/research/testing/eclat
 *
 * SVNROOT: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/.SVNREPOS/
 * ~/research/typequals/igj
 * ~/research/typequals/annotations-papers
 *
 * SVNREPOS: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/abb/REPOS
 * ~/prof/grants/2008-06-abb/abb
 *
 * HGREPOS: https://checker-framework.googlecode.com/hg/
 * ~/research/types/checker-framework
 *
 * SVNROOT: svn+ssh://login.csail.mit.edu/afs/csail/u/d/dannydig/REPOS/
 * ~/research/concurrency/concurrentPaper
 * ~/research/concurrency/mit.edu.refactorings concRefactor/project/mit.edu.refactorings</pre>
 *
 * <p>Furthermore, these 2 sections have identical effects:
 *
 * <pre>
 * SVNROOT: https://crashma.googlecode.com/svn/
 * ~/research/crashma trunk
 *
 * SVNREPOS: https://crashma.googlecode.com/svn/trunk
 * ~/research/crashma</pre>
 *
 * <p>and, all 3 of these sections have identical effects:
 *
 * <pre>
 * SVNROOT: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/
 * ~/research/typequals/annotations
 *
 * SVNROOT: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/
 * ~/research/typequals/annotations annotations
 *
 * SVNREPOS: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/annotations
 * ~/research/typequals/annotations</pre>
 *
 * <p id="installation"><b>Installation</b>
 *
 * <pre>
 * git clone https://github.com/plume-lib/multi-version-control
 * cd multi-version-control
 * ./gradlew shadowJar
 *
 * alias mvc='java -ea -cp CURRENT_DIR/build/libs/multi-version-control-all.jar org.plumelib.multiversioncontrol.MultiVersionControl'
 * </pre>
 */

// TODO:

// It might be nice to list all the "unexpected" checkouts -- those found
// on disk that are not in the checkouts file.  This permits the checkouts
// file to be updated and then used in preference to searching the whole
// filesystem, which may be slow.
// You can do this from the command line by comparing the output of these
// two commands:
//   mvc list --repositories /dev/null | sort > checkouts-from-directory
//   mvc list --search=false | sort > checkouts-from-file
// but it might be nicer for the "list" command to do that explicitly.

// The "list" command should be in the .mvc-checkouts file format, rather
// than requiring the user to munge it.

// In checkouts file, use of space delimiter for specifyng module interacts
// badly with file names that contain spaces.  This doesn't seem important
// enough to fix.

// When discovering checkouts from a directory structure, there is a
// problem when two modules from the same SVN repository are checked out,
// with one checkout inside the other at the top level.  The inner
// checkout's directory can be mis-reported as the outer one.  This isn't
// always a problem for nested checkouts (so it's hard to reproduce), and
// nested checkouts are bad style anyway, so I am deferring
// investigating/fixing it.

// Add "incoming" command that shows you need to do update and/or fetch?
//
// For Mercurial, I can do "hg incoming", but how to show that the current
// working directory is not up to date with respect to the local
// repository?  "hg prompt" with the "update" tag will do the trick, see
// http://bitbucket.org/sjl/hg-prompt/src/ .  Or don't bother:  it's rarely an
// issue if you always update via "hg fetch" as done by this program.
//
// For svn, "svn status -u":
//   The out-of-date information appears in the ninth column (with -u):
//       '*' a newer revision exists on the server
//       ' ' the working copy is up to date

public class MultiVersionControl {

  /** User home directory. [default Java {@code user.home} property]. */
  @OptionGroup("Configuration file")
  @Option(value = "User home directory.", noDocDefault = true)
  public static String home = System.getProperty("user.home");

  /**
   * File with list of clones. Set it to /dev/null to suppress reading. [default {@code
   * .mvc-checkouts} in home directory]
   */
  @Option(
      value = "File with list of clones.  Set it to /dev/null to suppress reading.",
      noDocDefault = true)
  public String checkouts = "~/.mvc-checkouts";

  /** If false, clone command skips existing directories. */
  @OptionGroup("Miscellaneous options")
  @Option("Redo existing clones; relevant only to clone command")
  public boolean redoExisting = false;

  /**
   * Terminating the process can leave the repository in a bad state, so set this rather high for
   * safety. Also, the timeout needs to account for the time to run hooks (that might recompile or
   * run tests).
   */
  @Option("Timeout for each command, in seconds")
  public int timeout = 600;

  // Default is false because searching whole directory structure is slow.
  /** If true, search for all clones, not just those listed in a file. */
  @OptionGroup("Searching for clones")
  @Option("Search for all clones, not just those listed in a file")
  public boolean search = false;

  /** If true, search for all clones whose directory is a prefix of one in the cofiguration file. */
  @Option("Search for all clones whose directory is a prefix of one listed in a file")
  public boolean searchPrefix = false;

  /**
   * Directory under which to search for clones, when using {@code --search} [default = home
   * directory].
   */
  @Option("Directory under which to search for clones; default=home dir")
  public List<String> dir = new ArrayList<>();

  /** Directories under which to NOT search for clones. May include leading "~/". */
  @Option("Directory under which to NOT search for clones")
  public List<String> ignoreDir = new ArrayList<>();

  /** Files, each a directory, corresponding to strings in {@link ignoreDir}. */
  private List<File> ignoreDirs = new ArrayList<>();

  // These *-executable command-line options are handy:
  //  * if you want to use a specific version of the program
  //  * if the program is not on your path
  //  * if there is a directory of the same name as the program, and . is on
  //    your path; in that case, the command would try to execute the directory.

  /** Path to the cvs program. */
  @OptionGroup("Paths to programs")
  @Option("Path to the cvs program")
  public String cvsExecutable = "cvs";

  /** Path to the git program. */
  @Option("Path to the git program")
  public String gitExecutable = "git";

  /** Path to the hg program. */
  @Option("Path to the hg program")
  public String hgExecutable = "hg";

  /** Path to the svn program. */
  @Option("Path to the svn program")
  public String svnExecutable = "svn";

  /** If true, use --insecure when invoking programs. */
  @Option("Pass --insecure argument to hg")
  public boolean insecure = false;

  // The {cvs,git,hg,svn}_arg options probably aren't very useful, because
  // there are few arguments that are applicable to every command; for
  // example, --insecure isn't applicable to "hg status".

  /** Extra argument to pass to the cvs program. */
  @Option("Extra argument to pass to the cvs program")
  public List<String> cvsArg = new ArrayList<>();

  /** Extra argument to pass to the git program. */
  @Option("Extra argument to pass to the git program")
  public List<String> gitArg = new ArrayList<>();

  /** Extra argument to pass to the hg program. */
  @Option("Extra argument to pass to the hg program")
  public List<String> hgArg = new ArrayList<>();

  /** Extra argument to pass to the svn program. */
  @Option("Extra argument to pass to the svn program")
  public List<String> svnArg = new ArrayList<>();

  // TODO: use consistent names: both "show" or both "print"

  /** If true, display each command is it is executed. */
  @Option("Display commands as they are executed")
  @OptionGroup("Diagnostics")
  public boolean show = false;

  /** If true, print the directory before executing commands in it. */
  @Option("Print the directory before executing commands")
  public boolean printDirectory = false;

  /** Perform a "dry run": print commands but do not execute them. */
  @Option("Do not execute commands; just print them.  Implies --show --redo-existing")
  public boolean dryRun = false;

  /** If true, run quietly (e.g., no output about missing directories). */
  @Option("-q Run quietly (e.g., no output about missing directories)")
  public boolean quiet = true;

  // It would be good to be able to set this per-clone.
  // This variable is static because it is used in static methods.
  /** Print debugging output. */
  @Option("Print debugging output")
  public static boolean debug = false;

  /** Debug 'replacers' that filter command output. */
  @Option("Debug 'replacers' that filter command output")
  public boolean debugReplacers = false;

  /** Lightweight debugging of 'replacers' that filter command output. */
  @Option("Lightweight debugging of 'replacers' that filter command output")
  public boolean debugProcessOutput = false;

  /** Actions that MultiVersionControl can perform. */
  static enum Action {
    /** Clone a repository. */
    CLONE,
    /** Show the working tree status. */
    STATUS,
    /** Pull changes from upstream. */
    PULL,
    /** List the known repositories. */
    LIST
  }

  // Shorter variants
  /** Clone a repository. */
  private static Action CLONE = Action.CLONE;

  /** Show the working tree status. */
  private static Action STATUS = Action.STATUS;

  /** Pull changes from upstream. */
  private static Action PULL = Action.PULL;

  /** List the known repositories. */
  private static Action LIST = Action.LIST;

  /** Which action to perform on this run of MultiVersionControl. */
  private Action action;

  /**
   * Replace "~" by the expansion of "$HOME".
   *
   * @param path the input path, which might contain "~"
   * @return path with "~" expanded
   */
  private static String expandTilde(String path) {
    return path.replaceFirst("^~", home);
  }

  /**
   * Runs a version control command, such as "status" or "update", on a <b>set</b> of CVS/Git/Hg/SVN
   * clones rather than just one.
   *
   * @param args the command-line arguments
   * @see MultiVersionControl
   */
  public static void main(String[] args) {
    setupSvnkit();
    MultiVersionControl mvc = new MultiVersionControl(args);

    Set<Checkout> checkouts = new LinkedHashSet<>();

    try {
      readCheckouts(new File(mvc.checkouts), checkouts, mvc.searchPrefix);
    } catch (IOException e) {
      System.err.println("Problem reading file " + mvc.checkouts + ": " + e.getMessage());
    }

    if (mvc.search) {
      // Postprocess command-line arguments
      for (String adir : mvc.ignoreDir) {
        File afile = new File(expandTilde(adir));
        if (!afile.exists()) {
          System.err.printf(
              "Warning: Directory to ignore while searching for checkouts does not exist:%n  %s%n",
              adir);
        } else if (!afile.isDirectory()) {
          System.err.printf(
              "Warning: Directory to ignore while searching for checkouts is not a directory:%n"
                  + "  %s%n",
              adir);
        } else {
          mvc.ignoreDirs.add(afile);
        }
      }

      for (String adir : mvc.dir) {
        adir = expandTilde(adir);
        if (debug) {
          System.out.println("Searching for checkouts under " + adir);
        }
        if (!new File(adir).isDirectory()) {
          System.err.printf(
              "Directory in which to search for checkouts is not a directory: %s%n", adir);
          System.exit(2);
        }
        int oldCheckouts = checkouts.size();
        findCheckouts(new File(adir), checkouts, mvc.ignoreDirs);
        if (debug) {
          System.out.printf("Searching added %d checkouts%n", checkouts.size() - oldCheckouts);
        }
      }
    }

    if (debug) {
      System.out.printf("About to process %d checkouts:%n", checkouts.size());
      for (Checkout c : checkouts) {
        System.out.println("  " + c);
      }
    }
    mvc.process(checkouts);
  }

  /** Set up the SVNKit library. */
  private static void setupSvnkit() {
    DAVRepositoryFactory.setup();
    SVNRepositoryFactoryImpl.setup();
    FSRepositoryFactory.setup();
  }

  /** Nullary constructor for use by OptionsDoclet. */
  @SuppressWarnings({
    "nullness", // initialization warning in unused constructor
    "initializedfields:contracts.postcondition" // initialization warning in unused constructor
  })
  private MultiVersionControl() {}

  /**
   * Create a MultiVersionControl instance.
   *
   * @param args the command-line arguments to MultiVersionControl
   */
  public MultiVersionControl(String[] args) {
    parseArgs(args);
  }

  /**
   * Parse the command-line arguments.
   *
   * @param args the command-line arguments
   * @see MultiVersionControl
   */
  @RequiresNonNull({"dir", "checkouts"})
  @EnsuresNonNull("action")
  @EnsuresInitializedFields(fields = "action")
  public void parseArgs(@UnknownInitialization MultiVersionControl this, String[] args) {
    @SuppressWarnings(
        "nullness:assignment" // new C(underInit) yields @UnderInitialization; @Initialized is safe
    )
    @Initialized Options options = new Options("mvc [options] {clone,status,pull,list}", this);
    String[] remainingArgs = options.parse(true, args);
    if (remainingArgs.length != 1) {
      System.out.printf(
          "Please supply exactly one argument (found %d)%n  %s%n",
          remainingArgs.length, String.join(" ", remainingArgs));
      options.printUsage();
      System.exit(1);
    }
    String actionString = remainingArgs[0];
    if ("checkout".startsWith(actionString)) {
      action = CLONE;
    } else if ("clone".startsWith(actionString)) {
      action = CLONE;
    } else if ("list".startsWith(actionString)) {
      action = LIST;
    } else if ("pull".startsWith(actionString)) {
      action = PULL;
    } else if ("status".startsWith(actionString)) {
      action = STATUS;
    } else if ("update".startsWith(actionString)) {
      action = PULL;
    } else {
      System.out.printf("Unrecognized action \"%s\"", actionString);
      options.printUsage();
      System.exit(1);
    }

    // clean up options

    checkouts = expandTilde(checkouts);

    if (dir.isEmpty()) {
      dir.add(home);
    }

    if (action == CLONE) {
      search = false;
      show = true;
      // Checkouts can be much slower than other operations.
      timeout = timeout * 10;

      // Set dryRun to true unless it was explicitly specified
      boolean explicitDryRun = false;
      for (String arg : args) {
        if (arg.startsWith("--dry-run") || arg.startsWith("--dryRun")) {
          explicitDryRun = true;
        }
      }
      if (!explicitDryRun) {
        if (!quiet) {
          System.out.println(
              "No --dry-run argument, so using --dry-run=true; override with --dry-run=false");
        }
        dryRun = true;
      }
    }

    if (dryRun) {
      show = true;
      redoExisting = true;
    }

    if (debug) {
      show = true;
    }
  }

  /** The types of repositories. */
  static enum RepoType {
    /** Bazaar. */
    BZR,
    /** CVS. */
    CVS,
    /** Git. */
    GIT,
    /** Mercurial. */
    HG,
    /** Subversion. */
    SVN
  }

  /** Class that represents a clone on the local file system. */
  static class Checkout {
    /** The type of repository to clone. */
    RepoType repoType;

    /** Local directory. */
    File directory;

    /** Local directory (canonical version). */
    String canonicalDirectory;

    /**
     * Non-null for CVS and SVN. May be null for distributed version control systems (Bzr, Git, Hg).
     * For distributed systems, refers to the parent repository from which this was cloned, not the
     * one here in this directory.
     *
     * <p>Most operations don't need this. It is needed for checkout, though.
     */
    @Nullable String repository;

    /**
     * Null if no module, just whole thing. Non-null for CVS and, optionally, for SVN. Null for
     * distributed version control systems (Bzr, Git, Hg).
     */
    @Nullable String module;

    /**
     * Create a Checkout.
     *
     * @param repoType the type of repository
     * @param directory where the new clone will appear
     */
    Checkout(RepoType repoType, File directory) {
      this(repoType, directory, null, null);
    }

    /**
     * Create a Checkout.
     *
     * @param repoType the type of repository
     * @param directory where the new clone will appear
     * @param repository the upstream repository
     * @param module the module that is checked out (for CVS and optionally SVN)
     */
    Checkout(
        RepoType repoType, File directory, @Nullable String repository, @Nullable String module) {
      // Directory might not exist if we are running the checkout command.
      // If it exists, it must be a directory.
      assert (directory.exists() ? directory.isDirectory() : true)
          : "Not a directory: " + directory;
      this.repoType = repoType;
      this.directory = directory;
      try {
        this.canonicalDirectory = directory.getCanonicalPath();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      this.repository = repository;
      this.module = module;
      // These asserts come at the end so that the error message can be better.
      switch (repoType) {
        case BZR:
          assertSubdirExists(directory, ".bzr");
          assert module == null;
          break;
        case CVS:
          assertSubdirExists(directory, "CVS");
          assert module != null : "No module for CVS checkout at: " + directory;
          break;
        case GIT:
          assertSubdirExists(directory, ".git");
          assert module == null;
          break;
        case HG:
          assertSubdirExists(directory, ".hg");
          assert module == null;
          break;
        case SVN:
          assertSubdirExists(directory, ".svn");
          assert module == null;
          break;
        default:
          assert false;
      }
    }

    /** An error indicating a version control directory (such as .git) does not exist. */
    static class DirectoryDoesNotExist extends Error {

      /** Unique identifier for serialization. If you add or remove fields, change this number. */
      static final long serialVersionUID = 20191205;

      /**
       * Create a new DirectoryDoesNotExist.
       *
       * @param msg a message about the missing directory
       */
      DirectoryDoesNotExist(String msg) {
        super(msg);
      }
    }

    /**
     * If the directory exists, then the subdirectory must exist too.
     *
     * @param directory the directory
     * @param subdirName a subdirectory that must exist, if {@code directory} exists
     */
    private static void assertSubdirExists(File directory, String subdirName) {
      if (directory.exists() && !new File(directory, subdirName).isDirectory()) {
        throw new DirectoryDoesNotExist(
            String.format(
                "Directory %s exists but %s subdirectory does not exist%n", directory, subdirName));
      }
    }

    @Override
    @Pure
    public boolean equals(@GuardSatisfied Checkout this, @GuardSatisfied @Nullable Object other) {
      if (!(other instanceof Checkout)) {
        return false;
      }
      Checkout c2 = (Checkout) other;
      return (repoType == c2.repoType)
          && canonicalDirectory.equals(c2.canonicalDirectory)
          && Objects.equals(module, c2.module);
    }

    @Override
    @Pure
    public int hashCode(@GuardSatisfied Checkout this) {
      return Objects.hash(repoType, canonicalDirectory, module);
    }

    @Override
    @SideEffectFree
    public String toString(@GuardSatisfied Checkout this) {
      return repoType + " " + directory + " " + repository + " " + module;
    }
  }

  // //////////////////////////////////////////////////////////////////////
  // Read checkouts from a file
  //

  /**
   * Read checkouts from the file (in {@code .mvc-checkouts} format), and add them to the set.
   *
   * @param file the .mvc-checkouts file
   * @param checkouts the set to populate; is side-effected by this method
   * @param searchPrefix if true, search for all clones whose directory is a prefix of one in the
   *     cofiguration file
   * @throws IOException if there is trouble reading the file (or file sysetm?)
   */
  @SuppressWarnings({
    "StringSplitter" // don't add dependence on Guava
  })
  static void readCheckouts(File file, Set<Checkout> checkouts, boolean searchPrefix)
      throws IOException {
    RepoType currentType = RepoType.BZR; // arbitrary choice, to avoid uninitialized variable
    String currentRoot = null;
    boolean currentRootIsRepos = false;

    try (EntryReader er = new EntryReader(file)) {
      for (String line : er) {
        if (debug) {
          System.out.println("line: " + line);
        }
        line = line.trim();
        // Skip comments and blank lines
        if (line.equals("") || line.startsWith("#")) {
          continue;
        }

        String[] splitTwo = line.split("[ \t]+");
        if (debug) {
          System.out.println("split length: " + splitTwo.length);
        }
        if (splitTwo.length == 2) {
          String word1 = splitTwo[0];
          String word2 = splitTwo[1];
          if (word1.equals("BZRROOT:") || word1.equals("BZRREPOS:")) {
            currentType = RepoType.BZR;
            currentRoot = word2;
            currentRootIsRepos = word1.equals("BZRREPOS:");
            continue;
          } else if (word1.equals("CVSROOT:")) {
            currentType = RepoType.CVS;
            currentRoot = word2;
            currentRootIsRepos = false;
            // If the CVSROOT is remote, try to make it local.
            if (currentRoot.startsWith(":ext:")) {
              String[] rootWords = currentRoot.split(":");
              String possibleRoot = rootWords[rootWords.length - 1];
              if (new File(possibleRoot).isDirectory()) {
                currentRoot = possibleRoot;
              }
            }
            continue;
          } else if (word1.equals("HGROOT:") || word1.equals("HGREPOS:")) {
            currentType = RepoType.HG;
            currentRoot = word2;
            currentRootIsRepos = word1.equals("HGREPOS:");
            continue;
          } else if (word1.equals("GITROOT:") || word1.equals("GITREPOS:")) {
            currentType = RepoType.GIT;
            currentRoot = word2;
            currentRootIsRepos = word1.equals("GITREPOS:");
            continue;
          } else if (word1.equals("SVNROOT:") || word1.equals("SVNREPOS:")) {
            currentType = RepoType.SVN;
            currentRoot = word2;
            currentRootIsRepos = word1.equals("SVNREPOS:");
            continue;
          }
        }

        if (currentRoot == null) {
          System.err.printf(
              "need root before directory at line %d of file %s%n",
              er.getLineNumber(), er.getFileName());
          System.exit(1);
        }

        String dirname;
        String root = currentRoot;
        if (root.endsWith("/")) {
          root = root.substring(0, root.length() - 1);
        }
        String module = null;

        int spacePos = line.lastIndexOf(' ');
        if (spacePos == -1) {
          dirname = line;
        } else {
          dirname = line.substring(0, spacePos);
          module = line.substring(spacePos + 1);
        }

        // The directory may not yet exist if we are doing a checkout.
        File dir = new File(expandTilde(dirname));

        if (module == null) {
          module = dir.getName();
        }
        if (currentType != RepoType.CVS) {
          if (!currentRootIsRepos) {
            root = root + "/" + module;
          }
          module = null;
        }

        Checkout checkout = new Checkout(currentType, dir, root, module);
        checkouts.add(checkout);

        // TODO: This can result in near-duplicates in the checkouts set.  Suppose that the
        // .mvc-checkouts file contains two lines
        //   /a/b/c
        //   /a/b/c-fork-d
        // with different repositories, and there exists a directory
        //   /a/b/c-fork-d-branch-e
        // Then the latter is included twice, once each with the repository of `c` and of
        // `c-fork-d`.
        if (searchPrefix) {
          String dirName = dir.getName();
          FileFilter namePrefixFilter =
              new FileFilter() {
                @Override
                public boolean accept(File file) {
                  return file.isDirectory() && file.getName().startsWith(dirName);
                }
              };
          File dirParent = dir.getParentFile();
          if (dirParent == null || !dirParent.isDirectory()) {
            continue;
          }
          File[] siblings = dirParent.listFiles(namePrefixFilter);
          if (siblings == null) {
            throw new Error(
                String.format(
                    "This cannot happen, because %s (parent of %s) is a directory",
                    dirParent, dir));
          }
          for (File sibling : siblings) {
            try {
              checkouts.add(new Checkout(currentType, sibling, root, module));
            } catch (Checkout.DirectoryDoesNotExist e) {
              // A directory is an extension of a file in
              // .mvc-checkouts, but lacks a (eg) .git subdir.  Just
              // skip that directory.
            }
          }
        }
      }
    } catch (IOException e) {
      System.err.printf("There is a problem with reading the file %s: %s", file.getPath(), e);
      throw new Error(e);
    }
    if (debug) {
      System.out.printf("Here are the checkouts:%n");
      for (Checkout c : checkouts) {
        System.out.printf("%s%n", c);
      }
    }
  }

  // //////////////////////////////////////////////////////////////////////
  // Find checkouts in a directory
  //

  // // Note:  this can be slow, because it examines every directory under your
  // // entire home directory.

  // Find checkouts.  These are indicated by directories named .bzr, CVS,
  // .hg, .git, or .svn.
  //
  // With some version control systems, this task is easy:  there is
  // exactly one .bzr, .hg, or .git directory per checkout.  With CVS and SVN,
  // there is one CVS/.svn directory per directory of the checkout.  It is
  // permitted for one checkout to be made inside another one (though that
  // is bad style), so we must examine every CVS/.svn directory to find all
  // the distinct checkouts.

  // TODO: This should use Files.walkFileTree, which is available since Java 7.

  //   /** Find all checkouts under the given directory. */
  //   static Set<Checkout> findCheckouts(File dir) {
  //     assert dir.isDirectory();
  //
  //     Set<Checkout> checkouts = new LinkedHashSet<>();
  //
  //     findCheckouts(dir, checkouts);
  //
  //     return checkouts;
  //   }

  /**
   * Find all checkouts at or under the given directory (or, as a special case, also its parent --
   * could rewrite to avoid that case), and adds them to checkouts. Works by checking whether dir or
   * any of its descendants is a version control directory.
   *
   * @param dir the directory under which to search for checkouts
   * @param checkouts the set to populate; is side-effected by this method
   * @param ignoreDirs directories not to search within
   */
  private static void findCheckouts(File dir, Set<Checkout> checkouts, List<File> ignoreDirs) {
    if (!dir.isDirectory()) {
      // This should never happen, unless the directory is deleted between
      // the call to findCheckouts and the test of isDirectory.
      if (debug) {
        System.out.println("findCheckouts: dir is not a directory: " + dir);
      }
      return;
    }
    if (ignoreDirs.contains(dir)) {
      if (debug) {
        System.out.println("findCheckouts: ignoring " + dir);
      }
      return;
    }

    String dirName = dir.getName().toString();
    File parent = dir.getParentFile();
    if (parent != null) {
      // The "return" statements below cause the code not to look for
      // checkouts inside version control directories.  (But it does look
      // for checkouts inside other checkouts.)  If someone checks in
      // a .svn file into a Mercurial repository, then removes it, the .svn
      // file remains in the repository even if not in the working copy.
      // That .svn file will cause an exception in dirToCheckoutSvn,
      // because it is not associated with a working copy.
      if (dirName.equals(".bzr")) {
        checkouts.add(new Checkout(RepoType.BZR, parent, null, null));
        return;
      } else if (dirName.equals("CVS")) {
        addCheckoutCvs(dir, parent, checkouts);
        return;
      } else if (dirName.equals(".hg")) {
        checkouts.add(dirToCheckoutHg(dir, parent));
        return;
      } else if (dirName.equals(".git")) {
        checkouts.add(dirToCheckoutGit(dir, parent));
        return;
      } else if (dirName.equals(".svn")) {
        Checkout c = dirToCheckoutSvn(parent);
        if (c != null) {
          checkouts.add(c);
        }
        return;
      }
    }

    @SuppressWarnings({
      "nullness" // dependent: listFiles => non-null because dir is a directory, and
      // the checker doesn't know that checkouts.add etc do not affect dir
    })
    File @NonNull [] childdirs = dir.listFiles(idf);
    if (childdirs == null) {
      System.err.printf(
          "childdirs is null (permission or other I/O problem?) for %s%n", dir.toString());
      return;
    }
    Arrays.sort(
        childdirs,
        new Comparator<File>() {
          @Override
          public int compare(File o1, File o2) {
            return o1.getName().compareTo(o2.getName());
          }
        });
    for (File childdir : childdirs) {
      findCheckouts(childdir, checkouts, ignoreDirs);
    }
  }

  /** Accept only directories that are not symbolic links. */
  static class IsDirectoryFilter implements FileFilter {
    /** Creates a new IsDirectoryFilter. */
    public IsDirectoryFilter() {}

    @Override
    public boolean accept(File pathname) {
      try {
        return pathname.isDirectory() && pathname.getPath().equals(pathname.getCanonicalPath());
      } catch (IOException e) {
        System.err.printf("Exception in IsDirectoryFilter.accept(%s): %s%n", pathname, e);
        throw new Error(e);
        // return false;
      }
    }
  }

  /** An IsDirectoryFilter. */
  static IsDirectoryFilter idf = new IsDirectoryFilter();

  /**
   * Given a directory named {@code CVS}, create a corresponding Checkout object for its parent, and
   * add it to the given set. (Google Web Toolkit does that, for example.)
   *
   * @param cvsDir a {@code CVS} directory
   * @param parentDir its parent
   * @param checkouts the set to populate; is side-effected by this method
   */
  static void addCheckoutCvs(File cvsDir, File parentDir, Set<Checkout> checkouts) {
    assert cvsDir.getName().toString().equals("CVS") : cvsDir.getName();
    // relative path within repository
    File repositoryFile = new File(cvsDir, "Repository");
    File rootFile = new File(cvsDir, "Root");
    if (!(repositoryFile.exists() && rootFile.exists())) {
      // apparently it wasn't a version control directory
      return;
    }

    String pathInRepo = FilesPlume.readString(repositoryFile.toPath()).trim();
    @NonNull File repoFileRoot = new File(pathInRepo);
    while (repoFileRoot.getParentFile() != null) {
      repoFileRoot = repoFileRoot.getParentFile();
    }

    // strip common suffix off of local dir and repo url
    FilePair stripped =
        removeCommonSuffixDirs(parentDir, new File(pathInRepo), repoFileRoot, "CVS");
    File dirRelative = stripped.file1;
    if (dirRelative == null) {
      System.out.printf("dir (%s) is parent of path in repo (%s)", parentDir, pathInRepo);
      System.exit(1);
    }
    String pathInRepoAtCheckout;
    if (stripped.file2 != null) {
      pathInRepoAtCheckout = stripped.file2.toString();
    } else {
      pathInRepoAtCheckout = dirRelative.getName();
    }

    String repoRoot = FilesPlume.readString(rootFile.toPath()).trim();
    checkouts.add(new Checkout(RepoType.CVS, dirRelative, repoRoot, pathInRepoAtCheckout));
  }

  /**
   * Given a directory named {@code .hg} , create a corresponding Checkout object for its parent.
   *
   * @param hgDir a {@code .hg} directory
   * @param parentDir its parent
   * @return a Checkout for the {@code .hg} directory
   */
  static Checkout dirToCheckoutHg(File hgDir, File parentDir) {
    String repository = null;

    File hgrcFile = new File(hgDir, "hgrc");
    Ini ini;
    // There also exist Hg commands that will do this same thing.
    if (hgrcFile.exists()) {
      try (BufferedReader bufferReader = Files.newBufferedReader(hgrcFile.toPath(), UTF_8)) {
        ini = new Ini(bufferReader);
        Profile.Section pathsSection = ini.get("paths");
        if (pathsSection != null) {
          repository = pathsSection.get("default");
          if (repository != null && repository.endsWith("/")) {
            repository = repository.substring(0, repository.length() - 1);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Problem reading file " + hgrcFile, e);
      }
    }

    return new Checkout(RepoType.HG, parentDir, repository, null);
  }

  /**
   * Given a directory named {@code .git}, create a corresponding Checkout object for its parent.
   *
   * @param gitDir a {@code .git} directory
   * @param parentDir its parent
   * @return a Checkout for the {@code .git} directory
   */
  static Checkout dirToCheckoutGit(File gitDir, File parentDir) {
    String repository = UtilPlume.backticks("git", "config", "remote.origin.url");

    return new Checkout(RepoType.GIT, parentDir, repository, null);
  }

  /**
   * Given a directory that contains a {@code .svn} subdirectory, create a corresponding Checkout
   * object. Returns null if this is not possible.
   *
   * @param parentDir a directory containing a {@code .svn} subdirectory
   * @return a SVN checkout for the directory, or null
   */
  static @Nullable Checkout dirToCheckoutSvn(File parentDir) {

    // For SVN, do
    //   svn info
    // and grep out these lines:
    // URL: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/myProj/repository/trunk/www
    // Repository Root: svn+ssh://login.csail.mit.edu/afs/csail/group/pag/projects/myProj/repository

    // Use SVNKit?
    // Con: introduces dependency on external library.
    // Pro: no need to re-implement or to call external process (which
    //   might be slow for large checkouts).

    @SuppressWarnings("nullness") // unannotated library: SVNKit
    SVNWCClient wcClient = new SVNWCClient((@Nullable ISVNAuthenticationManager) null, null);
    SVNInfo info;
    try {
      info = wcClient.doInfo(new File(parentDir.toString()), SVNRevision.WORKING);
    } catch (SVNException e) {
      // throw new Error("Problem in dirToCheckoutSvn(" + parentDir + "): ", e);
      System.err.println("Problem in dirToCheckoutSvn(" + parentDir + "): " + e.getMessage());
      if (e.getMessage() != null && e.getMessage().contains("This client is too old")) {
        System.err.println("plume-lib needs a newer version of SVNKit.");
      }
      return null;
    }
    // getFile is null when operating on a working copy, as I am
    // String relativeFile = info.getPath(); // relative to repository root; use to determine root
    // getFile is just the (absolute) local file name for local items -- same as "parentDir"
    // File relativeFile = info.getFile();
    SVNURL url = info.getURL();
    // This can be null, but I don't know under what circumstances. (example: parentDir
    // /afs/csail.mit.edu/u/m/mernst/.snapshot/class/6170/2006-spring/3dphysics)
    SVNURL repoRoot = info.getRepositoryRootURL();
    if (repoRoot == null) {
      System.err.println("Problem:  old svn working copy in " + parentDir.toString());
      System.err.println(
          "Check it out again to get a 'Repository Root' entry in the svn info output.");
      System.err.println("  repoUrl = " + url);
      System.exit(2);
    }
    if (debug) {
      System.out.println();
      System.out.println("repoRoot = " + repoRoot);
      System.out.println(" repoUrl = " + url);
      System.out.println("     parentDir = " + parentDir.toString());
    }

    // Strip common suffix off of local dir and repo url.
    FilePair stripped =
        removeCommonSuffixDirs(
            parentDir, new File(url.getPath()), new File(repoRoot.getPath()), ".svn");
    File dirRelative = stripped.file1;
    if (dirRelative == null) {
      System.out.printf("dir (%s) is parent of repository URL (%s)", parentDir, url.getPath());
      System.exit(1);
    }
    if (stripped.file2 == null) {
      System.out.printf("dir (%s) is child of repository URL (%s)", parentDir, url.getPath());
      System.exit(1);
    }
    String pathInRepoAtCheckout = stripped.file2.toString();
    try {
      url = url.setPath(pathInRepoAtCheckout, false);
    } catch (SVNException e) {
      throw new Error(e);
    }

    if (debug) {
      System.out.println("stripped: " + stripped);
      System.out.println("repoRoot = " + repoRoot);
      System.out.println(" repoUrl = " + url);
      System.out.println("    dirRelative = " + dirRelative.toString());
    }

    assert url.toString().startsWith(repoRoot.toString()) : "repoRoot=" + repoRoot + ", url=" + url;
    return new Checkout(RepoType.SVN, dirRelative, url.toString(), null);

    // // Old implementation
    // String module = url.toString().substring(repoRoot.toString().length());
    // if (module.startsWith("/")) {
    //   module = module.substring(1);
    // }
    // if (module.equals("")) {
    //   module = null;
    // }
    // return new Checkout(RepoType.SVN, dirRelative, repoRoot.toString(), module);

  }

  /** A pair of two files. */
  static class FilePair {
    /** The first file. */
    final @Nullable File file1;

    /** The second file. */
    final @Nullable File file2;

    /**
     * Create a FilePair.
     *
     * @param file1 the first file
     * @param file2 the second file
     */
    FilePair(@Nullable File file1, @Nullable File file2) {
      this.file1 = file1;
      this.file2 = file2;
    }
  }

  /**
   * Strip identical elements off the end of both paths, and then return what is left of each.
   * Returned elements can be null! If p2Limit is non-null, then it should be a parent of p2, and
   * the stripping stops when p2 becomes p2Limit. If p1Contains is non-null, then p1 must contain a
   * subdirectory of that name, and stripping stops when it is reached
   *
   * @param p1 the first path
   * @param p2 the first path
   * @param p2Limit null, or a parent of p2, which is the minimum suffix to return
   * @param p1Contains null, or a subdirectory of p1
   * @return p1 and p2, relative to their largest common prefix (modulo {@code p2Limit} and {@code
   *     p1Contains})
   */
  static FilePair removeCommonSuffixDirs(File p1, File p2, File p2Limit, String p1Contains) {
    if (debug) {
      System.out.printf("removeCommonSuffixDirs(%s, %s, %s, %s)%n", p1, p2, p2Limit, p1Contains);
    }
    // new names for results, because we will be side-effecting them
    File r1 = p1;
    File r2 = p2;
    while (r1 != null
        && r2 != null
        && (p2Limit == null || !r2.equals(p2Limit))
        && r1.getName().equals(r2.getName())) {
      if (p1Contains != null && !new File(r1.getParentFile(), p1Contains).isDirectory()) {
        break;
      }
      r1 = r1.getParentFile();
      r2 = r2.getParentFile();
    }
    if (debug) {
      System.out.printf("removeCommonSuffixDirs => %s %s%n", r1, r2);
    }
    return new FilePair(r1, r2);
  }

  // //////////////////////////////////////////////////////////////////////
  // Process checkouts
  //

  /**
   * Change pb's command by adding the given argument at the end.
   *
   * @param pb the ProcessBuilder to modify
   * @param arg the argument to add to {@code pb}'s command
   */
  private void addArg(ProcessBuilder pb, String arg) {
    List<String> command = pb.command();
    command.add(arg);
    pb.command(command);
  }

  /**
   * Change pb's command by adding the given arguments at the end.
   *
   * @param pb the ProcessBuilder to modify
   * @param args the arguments to add to {@code pb}'s command
   */
  private void addArgs(ProcessBuilder pb, List<String> args) {
    List<String> command = pb.command();
    command.addAll(args);
    pb.command(command);
  }

  /**
   * A Replacer does string substitution, to make output more user-friendly. Examples are
   * suppressing noise output or expanding relative file names.
   */
  private static class Replacer {
    /** The regular expression matching text that should be replaced. */
    Pattern regexp;

    /** The replacement text. */
    String replacement;

    /**
     * Create a new Replacer.
     *
     * @param regexp the regular expression matching text that should be replaced
     * @param replacement the replacement text
     */
    public Replacer(@Regex String regexp, String replacement) {
      this.regexp = Pattern.compile(regexp);
      this.replacement = replacement;
    }

    /**
     * Perform replacements on the given string. This method is less prone to StackOverflowError
     * than the JDK's {@code String.replaceAll}.
     *
     * @param s the string in which to perform replacements
     * @return the string, after replacements have been performed
     */
    public String replaceAll(String s) {
      Matcher matcher = regexp.matcher(s);
      return matcher.replaceAll(replacement);
    }
  }

  /**
   * Run the action described by field {@code action}, for each of the clones in {@code checkouts}.
   *
   * @param checkouts the clones and checkouts to process
   */
  public void process(Set<Checkout> checkouts) {
    // Always run at least one command, but sometimes up to three.
    ProcessBuilder pb = new ProcessBuilder("");
    pb.redirectErrorStream(true);
    ProcessBuilder pb2 = new ProcessBuilder(new ArrayList<String>());
    pb2.redirectErrorStream(true);
    ProcessBuilder pb3 = new ProcessBuilder(new ArrayList<String>());
    pb3.redirectErrorStream(true);
    // pb4 is only for checking whether there are no commits in this branch.
    ProcessBuilder pb4 = new ProcessBuilder(new ArrayList<String>());
    pb4.redirectErrorStream(true);

    // I really want to be able to redirect output to a Reader, but that
    // isn't possible.  I have to send it to a file.
    // I can't just use the InputStream directly, because if the process is
    // killed because of a timeout, the stream is inaccessible.

    CLONELOOP:
    for (Checkout c : checkouts) {
      if (debug) {
        System.out.println(c);
      }
      File dir = c.directory;

      List<Replacer> replacers = new ArrayList<>();
      List<Replacer> replacers3 = new ArrayList<>();

      switch (c.repoType) {
        case BZR:
          break;
        case CVS:
          replacers.add(new Replacer("(^|\\n)([?]) ", "$1$2 " + dir + "/"));
          break;
        case GIT:
          replacers.add(new Replacer("(^|\\n)fatal:", "$1fatal in " + dir + ":"));
          replacers.add(new Replacer("(^|\\n)warning:", "$1warning in " + dir + ":"));
          replacers.add(
              new Replacer(
                  "(^|\\n)(There is no tracking information for the current branch\\.)",
                  "$1" + dir + ": $2"));
          replacers.add(
              new Replacer("(^|\\n)(Your configuration specifies to merge)", dir + ": $1$2"));
          break;
        case HG:
          // "real URL" is for bitbucket.org.  (Should be early in list.)
          replacers.add(new Replacer("(^|\\n)real URL is .*\\n", "$1"));
          replacers.add(new Replacer("(^|\\n)(abort: .*)", "$1$2: " + dir));
          replacers.add(new Replacer("(^|\\n)([MARC!?I]) ", "$1$2 " + dir + "/"));
          replacers.add(
              new Replacer(
                  "(^|\\n)(\\*\\*\\* failed to import extension .*: No module named demandload\\n)",
                  "$1"));
          // Hack, should be replaced when googlecode certificate problems are fixed.
          replacers.add(
              new Replacer(
                  "(^|\\n)warning: .* certificate not verified"
                      + " \\(check web.cacerts config setting\\)\\n",
                  "$1"));
          // May appear twice in output with overlapping matches, so repeat the replacer
          replacers.add(
              new Replacer(
                  "(^|\\n)warning: .* certificate not verified"
                      + " \\(check web.cacerts config setting\\)\\n",
                  "$1"));
          // Does this mask too many errors?
          replacers.add(
              new Replacer(
                  "(^|\\n)((comparing with default-push\\n)?"
                      + "abort: repository default(-push)? not found!: .*\\n)",
                  "$1"));
          break;
        case SVN:
          replacers.add(
              new Replacer("(svn: Network connection closed unexpectedly)", "$1 for " + dir));
          replacers.add(new Replacer("(svn: Repository) (UUID)", "$1 " + dir + " $2"));
          replacers.add(
              new Replacer(
                  "(svn: E155037: Previous operation has not finished; run 'cleanup' if it was"
                      + " interrupted)",
                  "$1; for " + dir));
          break;
        default:
          assert false;
      }
      // The \r* is necessary here; (somtimes?) there are two carriage returns.
      replacers.add(
          new Replacer(
              "(remote: )?Warning: untrusted X11 forwarding setup failed: xauth key data not"
                  + " generated\r*\n"
                  + "(remote: )?Warning: No xauth data; using fake authentication data for X11"
                  + " forwarding\\.\r*\n",
              ""));
      replacers.add(new Replacer("(working copy ')", "$1" + dir));

      pb.command("echo", "command", "not", "set");
      pb.directory(dir);
      pb2.command(new ArrayList<String>());
      pb2.directory(dir);
      pb3.command(new ArrayList<String>());
      pb3.directory(dir);
      pb4.command(new ArrayList<String>());
      pb4.directory(dir);
      boolean showNormalOutput = false;
      // Set pb.command() to be the command to be executed.
      switch (action) {
        case LIST:
          System.out.println(c);
          continue CLONELOOP;
        case CLONE:
          pb.directory(dir.getParentFile());
          String dirbase = dir.getName();
          if (c.repository == null) {
            System.out.printf("Skipping checkout with unknown repository:%n  %s%n", dir);
            continue CLONELOOP;
          }
          switch (c.repoType) {
            case BZR:
              System.out.println("bzr handling not yet implemented: skipping " + c.directory);
              break;
            case CVS:
              assert c.module != null : "@AssumeAssertion(nullness): dependent type CVS";
              pb.command(
                  cvsExecutable,
                  "-d",
                  c.repository,
                  "checkout",
                  "-P", // prune empty directories
                  "-ko", // no keyword substitution
                  c.module);
              addArgs(pb, cvsArg);
              break;
            case GIT:
              // "--" is to prevent the directory name from being interpreted as a command-line
              // option, if it starts with a hyphen.
              // "--filter=blob:none" makes cloning fast and reduces disk space.  It makes a
              // subsequent `git blame` command slower, since it has retrieve information from the
              // remote repository.  It makes pulling from the cloned repository impossible.
              pb.command(gitExecutable, "clone", "--recursive", "--", c.repository, dirbase);
              addArgs(pb, gitArg);
              break;
            case HG:
              pb.command(hgExecutable, "clone", c.repository, dirbase);
              addArgs(pb, hgArg);
              if (insecure) {
                addArg(pb, "--insecure");
              }
              break;
            case SVN:
              if (c.module != null) {
                pb.command(svnExecutable, "checkout", c.repository, c.module);
              } else {
                pb.command(svnExecutable, "checkout", c.repository);
              }
              addArgs(pb, svnArg);
              break;
            default:
              assert false;
          }
          break;
        case STATUS:
          // I need a replacer for other version control systems, to add
          // directory names.
          showNormalOutput = true;
          switch (c.repoType) {
            case BZR:
              System.out.println("bzr handling not yet implemented: skipping " + c.directory);
              break;
            case CVS:
              assert c.repository != null;
              pb.command(
                  cvsExecutable,
                  "-q",
                  // Including "-d REPOS" seems to give errors when a
                  // subdirectory is in a different CVS repository.
                  // "-d", c.repository,
                  "diff",
                  "-b", // compress whitespace
                  "--brief", // report only whether files differ, not details
                  "-N"); // report new files
              addArgs(pb, cvsArg);
              //         # For the last perl command, this also works:
              //         #   perl -p -e 'chomp(\$cwd = `pwd`); s/^Index: /\$cwd\\//'";
              //         # but the one we use is briefer and uses the abbreviated directory name.
              //         $filter = "grep -v \"unrecognized keyword 'UseNewInfoFmtStrings'\" | grep
              // \"^Index:\" | perl -p -e 's|^Index: |$dir\\/|'";
              String removeRegexp =
                  ("\n=+"
                      + "\nRCS file: .*" // no trailing ,v for newly-created files
                      + "(\nretrieving revision .*)?" // no output for newly-created files
                      + "\ndiff .*"
                      + "(\nFiles .* and .* differ)?" // no output if only whitespace differences
                  );
              replacers.add(new Replacer(removeRegexp, ""));
              replacers.add(new Replacer("(^|\\n)Index: ", "$1" + dir + "/"));
              replacers.add(
                  new Replacer("(^|\\n)(cvs \\[diff aborted)(\\]:)", "$1$2 in " + dir + "$3"));
              replacers.add(new Replacer("(^|\\n)(Permission denied)", "$1$2 in " + dir));
              replacers.add(
                  new Replacer(
                      "(^|\\n)(cvs diff: )(cannot find revision control)",
                      "$1$2 in " + dir + ": $3"));
              replacers.add(new Replacer("(^|\\n)(cvs diff: cannot find )", "$1$2" + dir));
              replacers.add(new Replacer("(^|\\n)(cvs diff: in directory )", "$1$2" + dir + "/"));
              replacers.add(new Replacer("(^|\\n)(cvs diff: ignoring )", "$1$2" + dir + "/"));
              break;
            case GIT:
              pb.command(gitExecutable, "status");
              addArgs(pb, gitArg);
              // Why was I using this option??
              // addArg(pb, "--untracked-files=no");
              addArg(pb, "--porcelain"); // experimenting with porcelain output
              replacers.add(
                  new Replacer(
                      "(^|\\n)On branch master\\n"
                          + "Your branch is up-to-date with 'origin/master'.\\n"
                          + "\\n?",
                      "$1"));
              replacers.add(
                  new Replacer("(^|\\n)nothing to commit,? working directory clean\\n", "$1"));
              replacers.add(
                  new Replacer(
                      "(^|\\n"
                          + ")no changes added to commit \\(use \"git add\" and/or \"git commit"
                          + " -a\"\\)\\n",
                      "$1"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)nothing added to commit but untracked files present"
                          + " \\(use \"git add\" to track\\)\\n",
                      "$1"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)nothing to commit \\(use -u to show untracked files\\)\n", "$1"));

              replacers.add(new Replacer("(^|\\n)#\\n", "$1"));
              replacers.add(new Replacer("(^|\\n)# On branch master\\n", "$1"));
              replacers.add(
                  new Replacer("(^|\\n)nothing to commit \\(working directory clean\\)\\n", "$1"));
              replacers.add(new Replacer("(^|\\n)# Changed but not updated:\\n", "$1"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)#   \\(use \"git add <file>...\""
                          + " to update what will be committed\\)\\n",
                      "$1"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)#   \\(use \"git checkout -- <file>...\""
                          + " to discard changes in working directory\\)\\n",
                      "$1"));
              replacers.add(new Replacer("(^|\\n)# Untracked files:\\n", "$1"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)#   \\(use \"git add <file>...\""
                          + " to include in what will be committed\\)\\n",
                      "$1"));

              replacers.add(new Replacer("(^|\\n)(#\tmodified:   )", "$1" + dir + "/"));
              // This must come after the above, since it matches a prefix of the above
              replacers.add(new Replacer("(^|\\n)(#\t)", "$1untracked: " + dir + "/"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)# Your branch is ahead of .*\\n",
                      "$1unpushed changesets: " + pb.directory() + "\n"));
              replacers.add(new Replacer("(^|\\n)([?][?]) ", "$1$2 " + dir + "/"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)([ACDMRU][ ACDMRTU]|[ ACDMRU][ACDMRTU]) ", "$1$2 " + dir + "/"));

              // Useful info, but don't bother to report it, for consistency with other VCSes
              replacers.add(
                  new Replacer(
                      "(^|\\n)# Your branch is behind .*\\n",
                      "$1unpushed changesets: " + pb.directory() + "\n"));

              // Could remove all other output, but this could suppress messages
              // replacers.add(new Replacer("(^|\\n)#.*\\n", "$1"));

              // Necessary because "git status --porcelain" does not report:
              //   # Your branch is ahead of 'origin/master' by 1 commit.
              // If you have pushed but not pulled, then this will report
              pb2.command(gitExecutable, "log", "--branches", "--not", "--remotes");
              addArgs(pb2, gitArg);
              replacers.add(
                  new Replacer(
                      "^commit .*(.*\\n)+", "unpushed commits: " + pb2.directory() + "\n"));

              // TODO: use pb3 to look for stashes, using `git stash list`.

              // TODO: use `if git merge-base --is-ancestor origin/master HEAD ; then ...` to
              // determine whether this branch has no changes and thus can be deleted.
              pb4.command(gitExecutable, "merge-base", "--is-ancestor", "origin/master", "HEAD");

              break;
            case HG:
              pb.command(hgExecutable, "status");
              addArgs(pb, hgArg);
              if (debug) {
                System.out.printf(
                    "invalidCertificate(%s) => %s%n", c.directory, invalidCertificate(c.directory));
              }
              if (invalidCertificate(c.directory)) {
                pb2.command(hgExecutable, "outgoing", "-l", "1", "--config", "web.cacerts=");
              } else {
                pb2.command(hgExecutable, "outgoing", "-l", "1");
              }
              addArgs(pb2, hgArg);
              if (insecure) {
                addArg(pb2, "--insecure");
              }
              // The third line is either "no changes found" or "changeset".
              replacers.add(
                  new Replacer(
                      "^comparing with .*\\nsearching for changes\\nchangeset[^\001]*",
                      "unpushed changesets: " + pb.directory() + "\n"));
              replacers.add(
                  new Replacer(
                      "^\\n?comparing with .*\\nsearching for changes\\nno changes found\n", ""));
              pb3.command(hgExecutable, "shelve", "-l");
              addArgs(pb3, hgArg);
              // Shelve is an optional extension, so don't print anything if not installed.
              replacers3.add(new Replacer("^hg: unknown command 'shelve'\\n(.*\\n)+", ""));
              replacers3.add(
                  new Replacer("^(.*\\n)+", "shelved changes: " + pb.directory() + "\n"));
              break;
            case SVN:
              // Handle some changes.
              // "svn status" outputs an eighth column, if you pass the --show-updates switch: [* ]
              replacers.add(
                  new Replacer("(^|\\n)([ACDIMRX?!~ ][CM ][L ][+ ][$ ]) *", "$1$2 " + dir + "/"));
              pb.command(svnExecutable, "status");
              addArgs(pb, svnArg);
              break;
            default:
              assert false;
          }
          break;
        case PULL:
          switch (c.repoType) {
            case BZR:
              System.out.println("bzr handling not yet implemented: skipping " + c.directory);
              break;
            case CVS:
              replacers.add(
                  new Replacer(
                      "(^|\\n)(cvs update: ((in|skipping) directory|conflicts found in )) +",
                      "$1$2 " + dir + "/"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)(Merging differences between 1.16 and 1.17 into )",
                      "$1$2 " + dir + "/"));
              assert c.repository != null;
              pb.command(
                  cvsExecutable,
                  // Including -d causes problems with CVS repositories
                  // that are embedded inside other repositories.
                  // "-d", c.repository,
                  "-Q",
                  "update",
                  "-d");
              addArgs(pb, cvsArg);
              //         $filter = "grep -v \"config: unrecognized keyword
              // 'UseNewInfoFmtStrings'\"";
              replacers.add(new Replacer("(cvs update: move away )", "$1" + dir + "/"));
              replacers.add(new Replacer("(cvs \\[update aborted)(\\])", "$1 in " + dir + "$2"));
              break;
            case GIT:
              replacers.add(new Replacer("(^|\\n)Already up-to-date\\.\\n", "$1"));
              replacers.add(new Replacer("(^|\\n)error:", "$1error in " + dir + ":"));
              replacers.add(
                  new Replacer(
                      "(^|\\n)Please, commit your changes or stash them before you can merge.\\n"
                          + "Aborting\\n",
                      "$1"));
              replacers.add(
                  new Replacer(
                      "((^|\\n)CONFLICT \\(content\\): Merge conflict in )", "$1" + dir + "/"));
              replacers.add(new Replacer("(^|\\n)([ACDMRU]\t)", "$1$2" + dir + "/"));
              pb.command(gitExecutable, "pull", "-q", "--recurse-submodules");
              addArgs(pb, gitArg);
              // prune branches; alternately do "git remote prune origin"; "git gc" doesn't do this.
              pb2.command(gitExecutable, "fetch", "-p");
              break;
            case HG:
              replacers.add(new Replacer("(^|\\n)([?!AMR] ) +", "$1$2 " + dir + "/"));
              replacers.add(new Replacer("(^|\\n)abort: ", "$1"));
              pb.command(hgExecutable, "-q", "update");
              addArgs(pb, hgArg);
              if (invalidCertificate(c.directory)) {
                pb2.command(hgExecutable, "-q", "fetch", "--config", "web.cacerts=");
              } else {
                pb2.command(hgExecutable, "-q", "fetch");
              }
              addArgs(pb2, hgArg);
              if (insecure) {
                addArg(pb2, "--insecure");
              }
              break;
            case SVN:
              replacers.add(new Replacer("(^|\\n)([?!AMR] ) +", "$1$2 " + dir + "/"));
              replacers.add(new Replacer("(svn: Failed to add file ')(.*')", "$1" + dir + "/$2"));
              assert c.repository != null;
              pb.command(svnExecutable, "-q", "update");
              addArgs(pb, svnArg);
              //         $filter = "grep -v \"Killed by signal 15.\"";
              break;
            default:
              assert false;
          }
          break;
        default:
          assert false;
      }

      // Check that the directory exists (OK if it doesn't for checkout).
      if (debug) {
        System.out.println(dir + ":");
      }
      if (dir.exists()) {
        if (action == CLONE && !redoExisting && !quiet) {
          System.out.println("Skipping checkout (dir already exists): " + dir);
          continue;
        }
      } else {
        // Directory does not exist
        File parent = dir.getParentFile();
        if (parent == null) {
          // This happens when dir is the root directory.
          // It doesn't happen merely when the parent doesn't yet exist.
          System.err.printf("Directory %s does not exist, and it has no parent%n", dir);
          continue;
        }
        switch (action) {
          case CLONE:
            if (!parent.exists()) {
              if (show) {
                if (!dryRun) {
                  System.out.printf(
                      "Parent directory %s does not exist%s%n",
                      parent, (dryRun ? "" : " (creating)"));
                } else {
                  System.out.printf("  mkdir -p %s%n", parent);
                }
              }
              if (!dryRun) {
                if (!parent.mkdirs()) {
                  System.err.println("Could not create directory: " + parent);
                  System.exit(1);
                }
              }
            }
            break;
          case STATUS:
          case PULL:
            if (!quiet) {
              System.out.println("Cannot find directory: " + dir);
            }
            continue CLONELOOP;
          case LIST:
          default:
            assert false;
        }
      }

      if (printDirectory) {
        System.out.println(dir + " :");
      }
      perform_command(pb, replacers, showNormalOutput);
      if (pb2.command().size() > 0) {
        perform_command(pb2, replacers, showNormalOutput);
      }
      if (pb3.command().size() > 0) {
        perform_command(pb3, replacers3, showNormalOutput);
      }
      // TODO:
      // if (pb4.command().size() > 0) {
      //   int isAncestorStatus = perform_command(pb4, replacers4, showNormalOutput);
      //   if (isAncestorStatus == 0) {
      //     // TODO: Output this message only for non-master branches.
      //     // System.out.println("No changes committed in " + dir);
      //   }
      // }
    }
  }

  /** Regex for matching the default path for a Mercurial clone. */
  private @Regex(1) Pattern defaultPattern = Pattern.compile("^default[ \t]*=[ \t]*(.*)");

  /**
   * Given a directory containing a Mercurial checkout/clone, return its default path. Return null
   * otherwise.
   *
   * @param dir a directory containing a Mercurial clone
   * @return the path for the Mercurial clone
   */
  // This implementation is not quite right because we didn't look for the
  // [path] section.  We could fix this by using a real ini reader or
  // calling "hg showconfig".  This hack is good enough for now.
  private @Nullable String defaultPath(File dir) {
    File hgrc = new File(new File(dir, ".hg"), "hgrc");
    try (EntryReader er = new EntryReader(hgrc, "^#.*", null)) {
      for (String line : er) {
        Matcher m = defaultPattern.matcher(line);
        if (m.matches()) {
          return m.group(1);
        }
      }
    } catch (IOException e) {
      // System.out.printf("IOException: " + e);
      return null;
    }
    return null;
  }

  /** A regular expression that matches a message about incalid certificates. */
  private Pattern invalidCertificatePattern =
      Pattern.compile("^https://[^.]*[.][^.]*[.]googlecode[.]com/hg$");

  /**
   * Returns true if there is an invalid certificate for the given directory.
   *
   * @param dir the directory to test
   * @return true if there is an invalid certificate for the given directory
   */
  private boolean invalidCertificate(File dir) {
    String defaultPath = defaultPath(dir);
    if (debug) {
      System.out.printf("defaultPath=%s for %s%n", defaultPath, dir);
    }
    if (defaultPath == null) {
      return false;
    }
    return (defaultPath.startsWith("https://hg.codespot.com/")
        || invalidCertificatePattern.matcher(defaultPath).matches());
  }

  /**
   * Perform {@code pb}'s command.
   *
   * @param pb the ProcessBuilder whose commands to run
   * @param replacers replacement to make it the output before displaying it, to reduce verbasity
   * @param showNormalOutput if true, then display the output even if the process completed
   *     normally. Ordinarily, output is displayed only if the process completed erroneously.
   * @return the status code: 0 for normal completion, non-zero for erroneous completion
   */
  int perform_command(ProcessBuilder pb, List<Replacer> replacers, boolean showNormalOutput) {
    if (show) {
      System.out.println(command(pb));
    }
    if (dryRun) {
      return 0;
    }
    // Perform the command

    // For debugging
    //  my $command_cwd_sanitized = $command_cwd;
    //  $command_cwd_sanitized =~ s/\//_/g;
    //  $tmpfile = "/tmp/cmd-output-$$-$command_cwd_sanitized";
    // my $command_redirected = "$command > $tmpfile 2>&1";

    // We have changed from using plume.TimeLimitProcess to using
    // the Apache Commons Exec package.  To simplify the conversion
    // we have kept the ProcessBuilder argument but now only use its
    // members to construct the Commons Exec objects.

    @SuppressWarnings({"value"}) // ProcessBuilder.command() returns a non-empty list
    String @MinLen(1) [] args = (String @MinLen(1) []) pb.command().toArray(new String[0]);
    CommandLine cmdLine = new CommandLine(args[0]); // constructor requires executable name
    @SuppressWarnings("nullness") // indices are in bounds, so no null values in resulting array
    String[] argArray = Arrays.copyOfRange(args, 1, args.length);
    cmdLine.addArguments(argArray);
    DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
    @SuppressWarnings("nullness") // defaults to non-null and was never reset
    @NonNull File defaultDirectory = pb.directory();
    DefaultExecutor executor =
        DefaultExecutor.builder().setWorkingDirectory(defaultDirectory).get();

    ExecuteWatchdog watchdog =
        ExecuteWatchdog.builder().setTimeout(Duration.ofSeconds(timeout)).get();
    executor.setWatchdog(watchdog);

    final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    PumpStreamHandler streamHandler =
        new PumpStreamHandler(outStream); // send both stderr and stdout
    executor.setStreamHandler(streamHandler);

    try {
      executor.execute(cmdLine, resultHandler);
    } catch (IOException e) {
      String msg = e.toString();
      if (msg.startsWith("java.io.IOException: Cannot run program \"")
          && msg.endsWith(", No such file or directory")) {
        System.err.println(msg.substring(21));
      } else {
        throw new Error(e);
      }
    }

    int exitValue;
    try {
      resultHandler.waitFor();
      exitValue = resultHandler.getExitValue();
    } catch (InterruptedException e) {
      throw new Error(e);
    }
    boolean timedOut = executor.isFailure(exitValue) && watchdog.killedProcess();

    if (timedOut) {
      System.out.printf("Timed out (limit: %ss):%n", timeout);
      System.out.println(command(pb));
      // Don't return; also show the output
    }

    // Under what conditions should the output be printed?
    //  * for status, always
    //  * whenever the process exited non-normally
    //  * when debugging
    //  * other circumstances?
    // I could try printing always, to better understand this question.
    if (showNormalOutput || exitValue != 0 || debugReplacers || debugProcessOutput) {
      // Filter then print the output.
      String output;
      try {
        String tmpOutput = outStream.toString(UTF_8);
        output = tmpOutput;
      } catch (RuntimeException e) {
        throw new Error("Exception getting process standard output");
      }

      if (debugReplacers || debugProcessOutput) {
        System.out.println("preoutput=<<<" + output + ">>>");
      }
      if (!output.equals("")) {
        boolean noReplacement = false;
        for (Replacer r : replacers) {
          String printableRegexp = r.regexp.toString().replace("\r", "\\r").replace("\n", "\\n");
          if (debugReplacers) {
            System.out.println("midoutput_pre[" + printableRegexp + "]=<<<" + output + ">>>");
          }
          String origOutput = output;
          // Don't loop, because some regexps will continue to match repeatedly
          try {
            output = r.replaceAll(output);
          } catch (StackOverflowError soe) {
            noReplacement = true;
          } catch (Throwable e) {
            System.out.println("Exception in replaceAll.");
            System.out.println("  defaultDirectory = " + defaultDirectory);
            System.out.println("  cmdLine = " + cmdLine);
            System.out.println("  regexp = " + printableRegexp);
            System.out.println("  orig output (size " + origOutput.length() + ") = " + origOutput);
            System.out.println("  output (size " + output.length() + ") = " + output);
            throw e;
          }
          if (debugReplacers) {
            System.out.println("midoutput_post[" + printableRegexp + "]=<<<" + output + ">>>");
          }
        }
        if (debugReplacers || debugProcessOutput) {
          System.out.println("postoutput=<<<" + output + ">>>");
        }
        if (debugReplacers) {
          for (int i = 0; i < Math.min(100, output.length()); i++) {
            System.out.println(
                i + ": " + (int) output.charAt(i) + "\n        \"" + output.charAt(i) + "\"");
          }
        }
        if (noReplacement) {
          System.out.println(
              "No replacement done in " + defaultDirectory + " because output is too long.");
        }
        if (output.startsWith("You are not currently on a branch.")) {
          System.out.println(pb.directory() + ":");
        }
        System.out.print(output);
        if (noReplacement) {
          System.out.println("End of output for " + defaultDirectory + ".");
        }
      }
    }

    return exitValue;
  }

  /**
   * Returns the shell command for a process.
   *
   * @param pb the process whose command to return
   * @return the shell command for the process
   */
  String command(ProcessBuilder pb) {
    return "  cd " + pb.directory() + "\n  " + StringsPlume.join(" ", pb.command());
  }

  /**
   * A stream of newlines. Used for processes that want input, when we don't want to give them input
   * but don't want them to simply hang.
   */
  static class StreamOfNewlines extends InputStream {
    /** Creates a new StreamOfNewlines. */
    public StreamOfNewlines() {}

    @Override
    public @GTENegativeOne int read() {
      return (int) '\n';
    }
  }
}
