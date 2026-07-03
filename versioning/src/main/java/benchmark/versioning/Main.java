package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command-line program that generates an RDF version graph from a handful of
 * parameters and exports it under each global merge policy.
 * <p>
 * For every policy ({@code UNION}, {@code INTERSECTION},
 * {@code SYMMETRIC_DIFFERENCE}) it: generates the graph (the same seed
 * produces the same DAG for every policy — only the datasets downstream of
 * the merges differ), exports each version to its own N-Quads file plus the
 * PROV-O description of the version graph ({@code provenance.ttl}), then
 * reloads the whole graph from that provenance (parsed with Apache Jena) and
 * prints a summary. The consistency assertions are exercised by the tests in
 * {@code src/test/java} (see {@code PolicyRoundTripConsistencyTest} and
 * {@code InconsistencyDetectionTest}).
 * <p>
 * When a rule set is given ({@code --rules}), each reloaded history is
 * additionally run through the <b>Inference validation</b> engine
 * ({@link InferenceValidator}) and its summary is printed; run
 * {@link InferenceValidationMain} for the detailed per-version and per-merge
 * reports (see {@code Version-history-inference-validation.md}).
 */
public class Main {

    /** Default directory where the versions and the PROV-O graphs are written. */
    static final String DEFAULT_EXPORT_DIR = "versions-export";

    private static final String USAGE = """
            Usage: benchmark.versioning.Main [options]
              --versions <n>        total number of versions in the graph (default 12)
              --branches <n>        number of branches (default 3)
              --merges <n>          number of merges (default 2)
              --initial-quads <n>   quads in the initial dataset of the root (default 20)
              --evolution <n>       quads changed between two versions (default 6)
              --seed <n>            random seed, for reproducible graphs (default 42)
              --export-dir <dir>    export directory (default versions-export)
              --rules <file>        also run the Inference validation of each exported history
                                    against these rules (SHACL shapes or an RDFS/OWL ontology)
              --rule-language <l>   shacl | rdfs | owl | auto (default auto)
            Constraints: versions >= branches + merges, and branches >= 2 when merges > 0.""";

    public static void main(String[] args) throws IOException {
        VersionGraphGenerator.Parameters parameters;
        Path exportDir;
        Path rulesFile;
        RuleLanguage ruleLanguage;
        try {
            Options options = Options.parse(args);
            parameters = options.parameters;
            exportDir = options.exportDir;
            rulesFile = options.rulesFile;
            ruleLanguage = options.ruleLanguage;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        InferenceValidator validator = null;
        if (rulesFile != null) {
            validator = ruleLanguage == null
                    ? InferenceValidator.fromFile(rulesFile)
                    : InferenceValidator.fromFile(rulesFile, ruleLanguage);
            System.out.println("Inference validation rules: " + rulesFile
                    + " (" + validator.getLanguage()
                    + ", " + validator.getLanguage().getAssumption() + " regime)");
        }
        System.out.println("Generation parameters: " + parameters);
        System.out.println("Export directory: " + exportDir.toAbsolutePath());

        // The structure of the generated DAG only depends on the parameters
        // (same seed, same DAG): only the datasets downstream of the merges
        // differ between policies.
        for (MergePolicy policy : MergePolicy.values()) {
            String subDir = policy.name().toLowerCase().replace('_', '-');
            generateAndExport(policy, parameters, exportDir.resolve(subDir), validator);
        }
    }

    /**
     * Generates the graph under the given policy, exports it (one N-Quads
     * file per version plus {@code provenance.ttl}), reloads it from the
     * generated PROV-O description (Apache Jena), prints a summary and, when
     * a validator is given, the Inference validation summary of the reloaded
     * history.
     */
    private static void generateAndExport(MergePolicy policy, VersionGraphGenerator.Parameters parameters,
                                          Path directory, InferenceValidator validator) throws IOException {
        System.out.println("\n--- " + policy + " policy ---");

        VersionGraph graph = VersionGraphGenerator.generate(parameters, policy);

        List<Path> files = VersionGraphWriter.writeEachVersionToDirectory(graph, directory);
        ProvOWriter.writeToFile(graph, directory.resolve(ProvOReader.PROVENANCE_FILE));
        System.out.println("Wrote " + files.size() + " version files and "
                + ProvOReader.PROVENANCE_FILE + " to " + directory);

        // Reload the version graph from the generated provenance.ttl.
        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(directory);
        List<Version> ordered = VersionGraphWriter.topologicalOrder(loaded.versions());
        if (ordered.size() <= 20) {
            for (Version v : ordered) {
                System.out.println("  loaded " + describe(v));
            }
        }
        System.out.println("Reloaded from " + ProvOReader.PROVENANCE_FILE + ": " + summarize(ordered));
        System.out.println("Reloaded graph is consistent: "
                + VersionConsistencyChecker.isConsistent(loaded.versions(), loaded.policy()));
        if (validator != null) {
            InferenceValidator.HistoryReport report = validator.validateHistory(loaded.versions());
            System.out.println("Inference validation (" + validator.getLanguage() + "): "
                    + report.summary());
        }
    }

    private static String describe(Version v) {
        String parents = v.getParents().isEmpty()
                ? "none"
                : v.getParents().stream().map(Version::getId).collect(Collectors.joining(", "));
        String lifecycle = v.getGeneratedAtTime() == null
                ? ""
                : ", generated: " + v.getGeneratedAtTime()
                        + (v.getInvalidatedAtTime() == null
                                ? ", still valid"
                                : ", invalidated: " + v.getInvalidatedAtTime());
        return kindOf(v) + " " + v.getId() + " (parents: " + parents
                + ", quads: " + v.getData().size() + lifecycle + ")";
    }

    private static String summarize(List<Version> versions) {
        long roots = versions.stream().filter(v -> v.getParents().isEmpty()).count();
        long transitions = versions.stream().filter(v -> v.getParents().size() == 1).count();
        long merges = versions.stream().filter(v -> v.getParents().size() >= 2).count();
        return versions.size() + " versions (" + roots + " root, "
                + transitions + " transitions, " + merges + " merges)";
    }

    private static String kindOf(Version v) {
        int parents = v.getParents().size();
        if (parents == 0) {
            return "root";
        }
        return parents == 1 ? "transition" : "merge (" + parents + " parents)";
    }

    /**
     * The command-line options: the generation parameters, the export
     * directory and the optional Inference validation rules.
     */
    private static final class Options {
        final VersionGraphGenerator.Parameters parameters;
        final Path exportDir;
        final Path rulesFile;
        final RuleLanguage ruleLanguage;

        private Options(VersionGraphGenerator.Parameters parameters, Path exportDir,
                        Path rulesFile, RuleLanguage ruleLanguage) {
            this.parameters = parameters;
            this.exportDir = exportDir;
            this.rulesFile = rulesFile;
            this.ruleLanguage = ruleLanguage;
        }

        static Options parse(String[] args) {
            int versions = 12;
            int branches = 3;
            int merges = 2;
            int initialQuads = 20;
            int evolution = 6;
            long seed = 42;
            Path exportDir = Path.of(DEFAULT_EXPORT_DIR);
            Path rulesFile = null;
            RuleLanguage ruleLanguage = null;

            for (int i = 0; i < args.length; i += 2) {
                String option = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for option " + option);
                }
                String value = args[i + 1];
                switch (option) {
                    case "--versions" -> versions = parseInt(option, value);
                    case "--branches" -> branches = parseInt(option, value);
                    case "--merges" -> merges = parseInt(option, value);
                    case "--initial-quads" -> initialQuads = parseInt(option, value);
                    case "--evolution" -> evolution = parseInt(option, value);
                    case "--seed" -> seed = parseLong(option, value);
                    case "--export-dir" -> exportDir = Path.of(value);
                    case "--rules" -> rulesFile = Path.of(value);
                    case "--rule-language" -> ruleLanguage = InferenceValidationMain.parseLanguage(value);
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            return new Options(new VersionGraphGenerator.Parameters(
                    versions, branches, merges, initialQuads, evolution, seed),
                    exportDir, rulesFile, ruleLanguage);
        }

        private static int parseInt(String option, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid value for " + option + ": " + value);
            }
        }

        private static long parseLong(String option, String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid value for " + option + ": " + value);
            }
        }
    }
}
