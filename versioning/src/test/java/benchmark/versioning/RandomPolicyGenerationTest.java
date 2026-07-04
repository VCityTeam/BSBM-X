package benchmark.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-merge policy modes of {@link VersionGraphGenerator} and the
 * policy-free {@link VersionConsistencyChecker}:
 * {@link VersionGraphGenerator#generateRandomPolicies} draws a policy per
 * merge, the policy of every merge travels through the PROV-O round-trip
 * ({@code provenance.ttl}), and the consistency check verifies each merge
 * against its own policy — no policy parameter needed, even for histories
 * mixing several policies.
 */
class RandomPolicyGenerationTest {

    private static final VersionGraphGenerator.Parameters PARAMETERS =
            new VersionGraphGenerator.Parameters(12, 3, 2, 20, 6, 42);

    @Test
    void everyRandomMergeCarriesAPolicyAndTheGraphIsConsistent() {
        VersionGraph graph = VersionGraphGenerator.generateRandomPolicies(PARAMETERS);

        List<Version> merges = graph.getVersions().stream()
                .filter(v -> v.getParents().size() >= 2)
                .toList();
        assertEquals(PARAMETERS.merges(), merges.size());
        for (Version merge : merges) {
            assertNotNull(merge.getMergePolicy(),
                    () -> "merge " + merge.getId() + " must record the policy it drew");
        }
        assertNull(graph.getGlobalPolicy(), "a random-policy graph has no global policy");
        assertTrue(VersionConsistencyChecker.isConsistent(graph.getVersions()));
    }

    @Test
    void randomModeKeepsTheDagStructureOfTheFixedPolicyRuns() {
        // Same seed, same DAG: the policy draws come from their own random
        // stream, so they cannot perturb the structure choices.
        Map<String, List<String>> random =
                structureOf(VersionGraphGenerator.generateRandomPolicies(PARAMETERS));
        for (MergePolicy policy : MergePolicy.values()) {
            assertEquals(random, structureOf(VersionGraphGenerator.generate(PARAMETERS, policy)),
                    () -> "same seed must give the same DAG under " + policy);
        }
    }

    @Test
    void randomPoliciesSurviveTheProvenanceRoundTrip(@TempDir Path dir) throws IOException {
        VersionGraph graph = VersionGraphGenerator.generateRandomPolicies(PARAMETERS);
        VersionGraphWriter.writeEachVersionToDirectory(graph, dir);
        ProvOWriter.writeToFile(graph, dir.resolve(ProvOReader.PROVENANCE_FILE));

        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(dir);
        Map<String, Version> generatedById = new HashMap<>();
        graph.getVersions().forEach(v -> generatedById.put(v.getId(), v));
        assertEquals(generatedById.size(), loaded.versions().size());

        Set<MergePolicy> used = EnumSet.noneOf(MergePolicy.class);
        for (Version reloaded : loaded.versions()) {
            Version original = generatedById.get(reloaded.getId());
            assertNotNull(original, () -> "unknown version reloaded: " + reloaded.getId());
            assertEquals(original.getData(), reloaded.getData(),
                    () -> "dataset of " + reloaded.getId() + " changed through the round-trip");
            assertEquals(original.getMergePolicy(), reloaded.getMergePolicy(),
                    () -> "merge policy of " + reloaded.getId() + " changed through the round-trip");
            if (reloaded.getMergePolicy() != null) {
                used.add(reloaded.getMergePolicy());
            }
        }
        assertEquals(used.size() == 1 ? used.iterator().next() : null, loaded.policy(),
                "the global policy of the reload is the single shared policy, or null when mixed");
        assertTrue(VersionConsistencyChecker.isConsistent(loaded.versions()),
                "each merge must be consistent under its own reloaded policy");
    }

    @Test
    void aHistoryMixingPoliciesRoundTripsAndIsCheckedMergeByMerge(@TempDir Path dir) throws IOException {
        // Deterministic mixed history, independent of the random draws: two
        // merges of the same parents under two different policies.
        VersionGraph graph = new VersionGraph(null);
        Version root = graph.createRoot("V0", Set.of(product()));
        Version a = graph.createTransition("VA", root, Set.of(offer()), Set.of());
        Version b = graph.createTransition("VB", root, Set.of(review()), Set.of());
        Version union = graph.createMerge("M1", List.of(a, b), MergePolicy.UNION);
        Version intersection = graph.createMerge("M2", List.of(a, b), MergePolicy.INTERSECTION);

        assertEquals(Set.of(product(), offer(), review()), union.getData());
        assertEquals(Set.of(product()), intersection.getData());

        VersionGraphWriter.writeEachVersionToDirectory(graph, dir);
        ProvOWriter.writeToFile(graph, dir.resolve(ProvOReader.PROVENANCE_FILE));
        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(dir);

        assertNull(loaded.policy(), "a mixed history has no single global policy");
        Map<String, Version> byId = new HashMap<>();
        loaded.versions().forEach(v -> byId.put(v.getId(), v));
        assertEquals(MergePolicy.UNION, byId.get("M1").getMergePolicy());
        assertEquals(MergePolicy.INTERSECTION, byId.get("M2").getMergePolicy());
        assertTrue(VersionConsistencyChecker.isConsistent(loaded.versions()),
                "each merge must be checked against its own policy");
    }

    @Test
    void mergesWithoutAPolicyAreRejectedOrReportedInconsistent() {
        VersionGraph graph = new VersionGraph(null);
        Version root = graph.createRoot("V0", Set.of(product()));
        Version a = graph.createTransition("VA", root, Set.of(offer()), Set.of());
        Version b = graph.createTransition("VB", root, Set.of(review()), Set.of());
        assertThrows(IllegalStateException.class, () -> graph.createMerge("M1", List.of(a, b)),
                "a graph without a global policy needs an explicit per-merge policy");
        assertThrows(IllegalArgumentException.class,
                () -> VersionGraphGenerator.generate(PARAMETERS, null),
                "the fixed-policy generator rejects a null policy");

        Version handBuilt = new Version("M1", Set.of(product(), offer(), review()), List.of(a, b));
        assertFalse(VersionConsistencyChecker.isConsistent(List.of(root, a, b, handBuilt)),
                "a merge carrying no policy cannot be verified");
    }

    private static Map<String, List<String>> structureOf(VersionGraph graph) {
        Map<String, List<String>> structure = new HashMap<>();
        for (Version v : graph.getVersions()) {
            structure.put(v.getId(), v.getParents().stream().map(Version::getId).sorted().toList());
        }
        return structure;
    }

    private static Quad product() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"));
    }

    private static Quad offer() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("price"), Vocabulary.literal("42.0"));
    }

    private static Quad review() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("review1"), Vocabulary.bsbm("reviewFor"), Vocabulary.ex("product1"));
    }
}
