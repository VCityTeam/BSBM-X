package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    // Named graphs of the RDF datasets. They are IRIs, deliberately
    // independent of the version identifiers (V0, V1, ...).
    private static final String GRAPH_PRODUCTS = "http://example.org/graph/products";
    private static final String GRAPH_OFFERS = "http://example.org/graph/offers";
    private static final String GRAPH_REVIEWS = "http://example.org/graph/reviews";

    /** Default directory where the versions and the PROV-O graphs are written. */
    private static final String DEFAULT_EXPORT_DIR = "versions-export";

    /** Default version graph definition file used to build the demo graph. */
    private static final String DEFAULT_GRAPH_FILE = "src/benchmark/versioning/version-graph.txt";

    public static void main(String[] args) throws IOException {
        // The directory where each version is written in a different file and
        // where the PROV-O description of each graph is generated.
        // It can be overridden with the first program argument.
        Path exportDir = Path.of(args.length > 0 ? args[0] : DEFAULT_EXPORT_DIR);

        // The version graph definition file used to build the demo graph.
        // The same file is replayed under each policy: only the merge states
        // differ. It can be overridden with the second program argument.
        Path graphFile = Path.of(args.length > 1 ? args[1] : DEFAULT_GRAPH_FILE);
        System.out.println("Version graph definition file: " + graphFile);
        System.out.println("Export directory: " + exportDir.toAbsolutePath());

        // For each policy: build the graph, export it (one file per version
        // plus provenance.ttl), then reload it from the generated PROV-O
        // description and verify its consistency.
        testPolicy(MergePolicy.UNION, graphFile, exportDir.resolve("union"));
        testPolicy(MergePolicy.INTERSECTION, graphFile, exportDir.resolve("intersection"));
        testPolicy(MergePolicy.SYMMETRIC_DIFFERENCE, graphFile, exportDir.resolve("symmetric-difference"));

        // Demonstrate that the checker detects a violation of the policy,
        // also through the PROV-O round-trip.
        System.out.println("\n--- Testing inconsistency detection ---");
        testInconsistencyDetection(exportDir.resolve("tampered"));
    }

    /**
     * Tests one global merge policy end to end, using the generated
     * {@code provenance.ttl} as the description of the version graph:
     * <ol>
     *   <li>build the graph from the definition file under the policy;</li>
     *   <li>export it: one file per version and the PROV-O description of
     *       the version graph ({@code provenance.ttl});</li>
     *   <li>reload the whole version graph from {@code provenance.ttl}
     *       (parsed with Apache Jena): the DAG structure and the policy come
     *       from the PROV-O description, the dataset S(v) of each version
     *       from its exported file;</li>
     *   <li>verify that the reloaded graph matches the built one and that it
     *       is consistent under the policy declared in the provenance.</li>
     * </ol>
     * The consistency check therefore runs on the graph as described by
     * {@code provenance.ttl}, not on the in-memory build.
     */
    private static void testPolicy(MergePolicy policy, Path graphFile, Path directory) throws IOException {
        System.out.println("\n--- Testing with " + policy + " Policy ---");

        // 1-2. Build the graph from the definition file and export it.
        VersionGraph graph = VersionGraphReader.read(graphFile, policy);
        exportGraph(graph, directory);

        // 3. Reload the version graph from the generated provenance.ttl.
        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(directory);
        for (Version v : VersionGraphWriter.topologicalOrder(loaded.versions())) {
            System.out.println("Loaded " + kindOf(v) + ": " + v);
            if (v.getParents().size() >= 2) {
                System.out.println(v.getId() + " named graphs: " + v.getNamedGraphs());
            }
        }
        if (loaded.policy() != policy) {
            throw new RuntimeException("provenance.ttl declares policy " + loaded.policy()
                    + " instead of " + policy);
        }

        // 4a. The round-trip must be lossless: every reloaded version must
        //     carry the same RDF dataset as the version that was built.
        Map<String, Version> builtById = new HashMap<>();
        graph.getVersions().forEach(v -> builtById.put(v.getId(), v));
        for (Version v : loaded.versions()) {
            Version original = builtById.get(v.getId());
            if (original == null || !v.getData().equals(original.getData())) {
                throw new RuntimeException("Reloaded version " + v.getId()
                        + " does not match the built graph");
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
        for (Path file : files) {
            System.out.println("Wrote version file: " + file);
        }
        Path provFile = directory.resolve(ProvOReader.PROVENANCE_FILE);
        ProvOWriter.writeToFile(graph, provFile);
        System.out.println("Wrote PROV-O graph:  " + provFile);
    }

    private static Quad quad(String subject, String predicate, String object, String graphName) {
        return new Quad(subject, predicate, object, graphName);
    }

    private static String kindOf(Version v) {
        int parents = v.getParents().size();
        if (parents == 0) {
            return "root";
        }
        return parents == 1 ? "transition" : "merge (" + parents + " parents)";
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
                quad("ex:product1", "rdf:type", "bsbm:Product", GRAPH_PRODUCTS)));
        Version v0 = new Version("V0", initialData, List.of());

        Set<Quad> branchAData = new HashSet<>(v0.getData());
        branchAData.add(quad("ex:offer1", "bsbm:price", "\"42.0\"", GRAPH_OFFERS));
        Version vA = new Version("VA", branchAData, List.of(v0));

        Set<Quad> branchBData = new HashSet<>(v0.getData());
        branchBData.add(quad("ex:review1", "bsbm:reviewFor", "ex:product1", GRAPH_REVIEWS));
        Version vB = new Version("VB", branchBData, List.of(v0));

        // Tampered merge: a parasitic quad is introduced during the merge,
        // violating the global UNION policy.
        Set<Quad> tamperedData = new HashSet<>(branchAData);
        tamperedData.addAll(branchBData);
        tamperedData.add(quad("ex:intruder", "rdf:type", "bsbm:Product", GRAPH_PRODUCTS));
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
}
