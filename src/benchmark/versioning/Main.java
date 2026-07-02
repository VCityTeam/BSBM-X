package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Main {

    /** Default directory where the versions and the PROV-O graphs are written. */
    private static final String DEFAULT_EXPORT_DIR = "versions-export";

    private static final String USAGE = """
            Usage: benchmark.versioning.Main [options]
              --versions <n>       total number of versions in the graph (default 12)
              --branches <n>       number of branches (default 3)
              --merges <n>         number of merges (default 2)
              --initial-quads <n>  quads in the initial dataset of the root (default 20)
              --evolution <n>      quads changed between two versions (default 6)
              --seed <n>           random seed, for reproducible graphs (default 42)
              --export-dir <dir>   export directory (default versions-export)
            Constraints: versions >= branches + merges, and branches >= 2 when merges > 0.""";

    public static void main(String[] args) throws IOException {
        VersionGraphGenerator.Parameters parameters;
        Path exportDir;
        try {
            Options options = Options.parse(args);
            parameters = options.parameters;
            exportDir = options.exportDir;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        System.out.println("Generation parameters: " + parameters);
        System.out.println("Export directory: " + exportDir.toAbsolutePath());

        // For each policy: generate the graph, export it (one file per
        // version plus provenance.ttl), then reload it from the generated
        // PROV-O description and verify its consistency. The structure of
        // the generated DAG only depends on the parameters (same seed, same
        // DAG): only the datasets downstream of the merges differ.
        testPolicy(MergePolicy.UNION, parameters, exportDir.resolve("union"));
        testPolicy(MergePolicy.INTERSECTION, parameters, exportDir.resolve("intersection"));
        testPolicy(MergePolicy.SYMMETRIC_DIFFERENCE, parameters, exportDir.resolve("symmetric-difference"));

        // Demonstrate that the checker detects a violation of the policy,
        // also through the PROV-O round-trip.
        System.out.println("\n--- Testing inconsistency detection ---");
        testInconsistencyDetection(exportDir.resolve("tampered"));
    }

    /**
     * Tests one global merge policy end to end, using the generated
     * {@code provenance.ttl} as the description of the version graph:
     * <ol>
     *   <li>generate the graph from the parameters under the policy;</li>
     *   <li>export it: one file per version and the PROV-O description of
     *       the version graph ({@code provenance.ttl});</li>
     *   <li>reload the whole version graph from {@code provenance.ttl}
     *       (parsed with Apache Jena): the DAG structure and the policy come
     *       from the PROV-O description, the dataset S(v) of each version
     *       from its exported file;</li>
     *   <li>verify that the reloaded graph matches the generated one and
     *       that it is consistent under the policy declared in the
     *       provenance.</li>
     * </ol>
     * The consistency check therefore runs on the graph as described by
     * {@code provenance.ttl}, not on the in-memory build.
     */
    private static void testPolicy(MergePolicy policy, VersionGraphGenerator.Parameters parameters,
                                   Path directory) throws IOException {
        System.out.println("\n--- Testing with " + policy + " Policy ---");

        // 1-2. Generate the graph and export it.
        VersionGraph graph = VersionGraphGenerator.generate(parameters, policy);
        exportGraph(graph, directory);

        // 3. Reload the version graph from the generated provenance.ttl.
        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(directory);
        List<Version> ordered = VersionGraphWriter.topologicalOrder(loaded.versions());
        if (ordered.size() <= 20) {
            for (Version v : ordered) {
                System.out.println("Loaded " + describe(v));
            }
        }
        System.out.println("Reloaded from provenance.ttl: " + summarize(ordered));
        if (loaded.policy() != policy) {
            throw new RuntimeException("provenance.ttl declares policy " + loaded.policy()
                    + " instead of " + policy);
        }

        // 4a. The round-trip must be lossless: every reloaded version must
        //     carry the same RDF dataset as the version that was generated.
        Map<String, Version> generatedById = new HashMap<>();
        graph.getVersions().forEach(v -> generatedById.put(v.getId(), v));
        for (Version v : loaded.versions()) {
            Version original = generatedById.get(v.getId());
            if (original == null || !v.getData().equals(original.getData())) {
                throw new RuntimeException("Reloaded version " + v.getId()
                        + " does not match the generated graph");
            }
        }
        System.out.println("Round-trip through provenance.ttl is lossless: "
                + loaded.versions().size() + " versions");

        // 4b. Consistency verification of the reloaded graph.
        boolean consistent = VersionConsistencyChecker.isConsistent(loaded.versions(), loaded.policy());
        System.out.println("Graph is consistent: " + consistent);
        if (!consistent) {
            throw new RuntimeException("Consistency check failed!");
        }
    }

    /**
     * Writes each version of the graph in a different file inside the
     * directory, and the PROV-O description of the version graph next to
     * them, as {@code provenance.ttl}.
     */
    private static void exportGraph(VersionGraph graph, Path directory) throws IOException {
        List<Path> files = VersionGraphWriter.writeEachVersionToDirectory(graph, directory);
        Path provFile = directory.resolve(ProvOReader.PROVENANCE_FILE);
        ProvOWriter.writeToFile(graph, provFile);
        System.out.println("Wrote " + files.size() + " version files and "
                + ProvOReader.PROVENANCE_FILE + " to " + directory);
    }

    private static String describe(Version v) {
        String parents = v.getParents().isEmpty()
                ? "none"
                : v.getParents().stream().map(Version::getId).collect(Collectors.joining(", "));
        return kindOf(v) + " " + v.getId() + " (parents: " + parents
                + ", quads: " + v.getData().size() + ")";
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

    private static Quad quad(String subject, String predicate, String object, String graphName) {
        return new Quad(subject, predicate, object, graphName);
    }

    /**
     * Builds a tampered merge (a parasitic quad is introduced during the
     * merge, violating the global UNION policy), exports it with its PROV-O
     * description, reloads it from {@code provenance.ttl} and verifies that
     * the checker detects the violation.
     */
    private static void testInconsistencyDetection(Path directory) throws IOException {
        MergePolicy policy = MergePolicy.UNION;

        Set<Quad> initialData = new HashSet<>(List.of(
                quad("ex:product1", "rdf:type", "bsbm:Product", VersionGraphGenerator.GRAPH_PRODUCTS)));
        Version v0 = new Version("V0", initialData, List.of());

        Set<Quad> branchAData = new HashSet<>(v0.getData());
        branchAData.add(quad("ex:offer1", "bsbm:price", "\"42.0\"", VersionGraphGenerator.GRAPH_OFFERS));
        Version vA = new Version("VA", branchAData, List.of(v0));

        Set<Quad> branchBData = new HashSet<>(v0.getData());
        branchBData.add(quad("ex:review1", "bsbm:reviewFor", "ex:product1", VersionGraphGenerator.GRAPH_REVIEWS));
        Version vB = new Version("VB", branchBData, List.of(v0));

        // Tampered merge: a parasitic quad is introduced during the merge,
        // violating the global UNION policy.
        Set<Quad> tamperedData = new HashSet<>(branchAData);
        tamperedData.addAll(branchBData);
        tamperedData.add(quad("ex:intruder", "rdf:type", "bsbm:Product", VersionGraphGenerator.GRAPH_PRODUCTS));
        Version badMerge = new Version("M_BAD", tamperedData, List.of(vA, vB));

        List<Version> versions = List.of(v0, vA, vB, badMerge);
        VersionGraphWriter.writeEachVersionToDirectory(versions, policy, directory);
        ProvOWriter.writeToFile(versions, policy, directory.resolve(ProvOReader.PROVENANCE_FILE));

        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(directory);
        boolean consistent = VersionConsistencyChecker.isConsistent(loaded.versions(), loaded.policy());
        System.out.println("Tampered graph is consistent: " + consistent);

        if (consistent) {
            throw new RuntimeException("Inconsistency was not detected!");
        }
    }

    /** The command-line options: the generation parameters and the export directory. */
    private static final class Options {
        final VersionGraphGenerator.Parameters parameters;
        final Path exportDir;

        private Options(VersionGraphGenerator.Parameters parameters, Path exportDir) {
            this.parameters = parameters;
            this.exportDir = exportDir;
        }

        static Options parse(String[] args) {
            int versions = 12;
            int branches = 3;
            int merges = 2;
            int initialQuads = 20;
            int evolution = 6;
            long seed = 42;
            Path exportDir = Path.of(DEFAULT_EXPORT_DIR);

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
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            return new Options(new VersionGraphGenerator.Parameters(
                    versions, branches, merges, initialQuads, evolution, seed), exportDir);
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
