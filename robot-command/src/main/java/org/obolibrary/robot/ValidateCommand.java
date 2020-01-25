package org.obolibrary.robot;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles command-line options for the {@link ValidateOperation}.
 *
 * @author <a href="mailto:consulting@michaelcuffaro.com">Michael Cuffaro</a>
 */
public class ValidateCommand implements Command {
  /** Logger */
  private static final Logger logger = LoggerFactory.getLogger(ValidateCommand.class);

  /** Used to store the command-line options for the command. */
  private Options options;

  /** Namespace for error messages. */
  private static final String NS = "validate#";

  private static final String tableNotProvidedError =
      NS + "TABLE NOT PROVIDED ERROR a table file must be specified to run this command";

  private static final String incorrectTableFormatError =
      NS + "INCORRECT TABLE FORMAT ERROR the table file must end in either .csv or .tsv";

  private static final String incorrectOutputFormatError =
      NS + "INCORRECT OUTPUT FORMAT ERROR the output format must be one of HTML, XLSX, or TXT";

  private static final String missingOutputDirectoryError =
      NS + "MISSING OUTPUT DIRECTORY ERROR output directory required when format is specified";

  private static final String invalidOutputDirectoryError =
      NS
          + "INVALID OUTPUT DIRECTORY ERROR the specified output directory does not exist or "
          + "is not writable";

  private static final String invalidSkipRowError =
      NS + "INVALID SKIP ROW ERROR the specified skip-row must be an integer";

  /** Constructor: Initialises the command with its various options. */
  public ValidateCommand() {
    Options o = CommandLineHelper.getCommonOptions();
    o.addOption("t", "table", true, "file containing data (in CSV or TSV format) to validate");
    o.addOption(
        "k",
        "skip-row",
        true,
        "ignore the given row from the tables to be validated "
            + "(where the first row in the file is row #1); this option is ignored if the row "
            + "to skip is greater than the total number of rows in a table");
    o.addOption("i", "input", true, "input file containing the ontology data to validate against");
    o.addOption(
        "r",
        "reasoner",
        true,
        "reasoner to use; must be one of: structural, hermit, jfact, "
            + "emr, elk (if left unspecified, the default reasoner will be used)");
    o.addOption(
        "o",
        "output-dir",
        true,
        "directory where output files will be saved (ignored if "
            + "format option is left unspecified)");
    o.addOption(
        "f",
        "format",
        true,
        "format for output file (XLSX, HTML, TXT) (if unspecified, "
            + "plain text output is sent to STDOUT)");
    o.addOption(
        "s",
        "standalone",
        true,
        "If true, and the output format is HTML, generate the HTML report as a standalone file "
            + "(this option is ignored if the output format is not HTML)");
    options = o;
  }

  /**
   * Returns the name of the command
   *
   * @return name
   */
  public String getName() {
    return "validate";
  }

  /**
   * Returns a brief description of the command.
   *
   * @return description
   */
  public String getDescription() {
    return "validate the data in the given file";
  }

  /**
   * Returns the command-line usage for the command.
   *
   * @return usage
   */
  public String getUsage() {
    return "validate --table <file> [--table <file> ...] [--skip-row k] --input <file> "
        + "[--reasoner <name>] [--format (HTML|XLSX|TXT)] [--output-dir <directory>] "
        + "[--standalone (true|false)]";
  }

  /**
   * Returns the command-line options for the command.
   *
   * @return options
   */
  public Options getOptions() {
    return options;
  }

  /**
   * Handles the command-line and file operations for the ValidateOperation
   *
   * @param args strings to use as arguments
   */
  public void main(String[] args) {
    try {
      execute(null, args);
    } catch (Exception e) {
      CommandLineHelper.handleException(e);
    }
  }

  /**
   * Accepts an input state and command line arguments and calls ValidateOperation to validate the
   * data that has been passed. Returns the final state of the command.
   *
   * @param state the state from the previous command, or null
   * @param args the command-line arguments
   * @return the input state, unchanged
   * @throws Exception on any problem
   */
  public CommandState execute(CommandState state, String[] args) throws Exception {
    CommandLine line = CommandLineHelper.getCommandLine(getUsage(), getOptions(), args);
    if (line == null) {
      return null;
    }

    if (state == null) {
      state = new CommandState();
    }

    // Get the input ontology either from the command line directly or as part of the command
    // chain:
    IOHelper ioHelper = CommandLineHelper.getIOHelper(line);
    state = CommandLineHelper.updateInputOntology(ioHelper, state, line);
    OWLOntology ontology = state.getOntology();

    // Get the reasoner specified by the user and if none is specified, use the default:
    if (CommandLineHelper.getOptionalValue(line, "reasoner") == null) {
      logger.info("No reasoner specified. Will use the default.");
    }
    OWLReasonerFactory reasonerFactory = CommandLineHelper.getReasonerFactory(line, true);

    // Get the requested output directory and output format, and the value of the standalone option.
    // Note that standalone only applies to html formatted output, and will be ignored by
    // ValidateOperation if html hasn't been specified.
    String outFormat = CommandLineHelper.getOptionalValue(line, "format");
    boolean standalone = CommandLineHelper.getBooleanValue(line, "standalone", false);
    String outDir = CommandLineHelper.getOptionalValue(line, "output-dir");

    // If an output format has been specified, make sure that it is of a supported kind and that
    // the output directory is valid. If it hasn't been specified it will just be passed as null to
    // the validate operation.
    if (outFormat != null) {
      if (!(outFormat.equalsIgnoreCase("html")
          || outFormat.equalsIgnoreCase("xlsx")
          || outFormat.equalsIgnoreCase("txt"))) {
        throw new IllegalArgumentException(incorrectOutputFormatError);
      }

      if (outDir == null) {
        throw new IllegalArgumentException(missingOutputDirectoryError);
      }

      File d = new File(outDir);
      if (!d.exists() || !d.isDirectory() || !d.canWrite()) {
        throw new IllegalArgumentException(invalidOutputDirectoryError);
      }
    }

    // Get the paths to the tables given in the --table arguments.
    List<String> tablePaths = CommandLineHelper.getOptionalValues(line, "table");
    if (tablePaths.isEmpty()) {
      throw new IllegalArgumentException(tableNotProvidedError);
    }

    // Extract all of the data from each of the given table paths. Only TSV and CSV tables
    // are currently supported.
    Map<String, List<List<String>>> tables = new HashMap();
    for (String tablePath : tablePaths) {
      List<List<String>> tableData;
      if (tablePath.toLowerCase().endsWith(".tsv")) {
        tableData = ioHelper.readTSV(tablePath);
      } else if (tablePath.toLowerCase().endsWith(".csv")) {
        tableData = ioHelper.readCSV(tablePath);
      } else {
        throw new IllegalArgumentException(incorrectTableFormatError);
      }

      // If the `--skip-row` switch has been specified, then possibly delete the specified row from
      // the table:
      String rowToSkipStr;
      if ((rowToSkipStr = CommandLineHelper.getOptionalValue(line, "skip-row")) != null) {
        try {
          int rowToSkip = Integer.parseInt(rowToSkipStr);
          if (rowToSkip > tableData.size()) {
            logger.warn(
                "ignoring skip-row value: {}; there are only {} rows in '{}'",
                rowToSkip,
                tableData.size(),
                tablePath);
          } else {
            tableData.remove(rowToSkip - 1);
          }
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(invalidSkipRowError);
        }
      }
      tables.put(tablePath, tableData);
    }

    // Finally send everything to the validate operation:
    ValidateOperation.validate(tables, ontology, reasonerFactory, outDir, outFormat, standalone);

    return state;
  }
}
