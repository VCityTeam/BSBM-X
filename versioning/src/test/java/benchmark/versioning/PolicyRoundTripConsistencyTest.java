package benchmark.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * End-to-end test of one global merge policy (formerly {@code Main.testPolicy}):
 * <ol>
 *   <li>generate the graph from the parameters under the policy;</li>
 *   <li>export it — one N-Quads file per version and the PROV-O description
 *       of the version graph ({@code provenance.ttl});</li>
 *   <li>reload the whole version graph from {@code provenance.ttl} (parsed
 *       with Apache Jena);</li>
 *   <li>assert the reload is lossless and that the reloaded graph is
 *       consistent under the policy declared in the provenance.</li>
 * </ol>
 * The consistency check therefore runs on the graph as described by
 * {@code provenance.ttl}, not on the in-memory build.
 */
class PolicyRoundTripConsistencyTest {

    private static final VersionGraphGenerator.Parameters PARAMETERS =
            new VersionGraphGenerator.Parameters(12, 3, 2, 20, 6, 42);

    @ParameterizedTest
    @EnumSource(MergePolicy.class)
    void roundTripIsLosslessAndConsistent(MergePolicy policy, @TempDir Path dir) throws IOException {
        // 1-2. Generate and export (versions + provenance.ttl).
        VersionGraph graph = VersionGraphGenerator.generate(PARAMETERS, policy);
        VersionGraphWriter.writeEachVersionToDirectory(graph, dir);
        ProvOWriter.writeToFile(graph, dir.resolve(ProvOReader.PROVENANCE_FILE));

        // 3. Reload the version graph from the generated provenance.ttl.
        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(dir);

        assertEquals(policy, loaded.policy(),
                "provenance.ttl must declare the policy it was written with");

        // 4a. The round-trip must be lossless: every reloaded version must
        //     carry the same RDF dataset and the same PROV-O lifecycle
        //     instants as the generated one.
        Map<String, Version> generatedById = new HashMap<>();
        graph.getVersions().forEach(v -> generatedById.put(v.getId(), v));
        assertEquals(generatedById.size(), loaded.versions().size(),
                "the reload must contain every generated version");
        for (Version reloaded : loaded.versions()) {
            Version original = generatedById.get(reloaded.getId());
            assertNotNull(original, () -> "unknown version reloaded: " + reloaded.getId());
            assertEquals(original.getData(), reloaded.getData(),
                    () -> "dataset of " + reloaded.getId() + " changed through the round-trip");
            assertNotNull(original.getGeneratedAtTime(),
                    () -> "generated version " + reloaded.getId() + " must carry a prov:generatedAtTime");
            assertEquals(original.getGeneratedAtTime(), reloaded.getGeneratedAtTime(),
                    () -> "prov:generatedAtTime of " + reloaded.getId() + " changed through the round-trip");
            assertEquals(original.getInvalidatedAtTime(), reloaded.getInvalidatedAtTime(),
                    () -> "prov:invalidatedAtTime of " + reloaded.getId() + " changed through the round-trip");
            assertEquals(original.getMergePolicy(), reloaded.getMergePolicy(),
                    () -> "merge policy of " + reloaded.getId() + " changed through the round-trip");
        }

        // 4b. Consistency verification of the reloaded graph: each merge is
        //     checked against its own policy, restored from provenance.ttl.
        assertTrue(VersionConsistencyChecker.isConsistent(loaded.versions()),
                () -> "reloaded graph must be consistent under " + policy);
    }
}
