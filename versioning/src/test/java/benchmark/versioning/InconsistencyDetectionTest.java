package benchmark.versioning;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Negative test (formerly {@code Main.testInconsistencyDetection}): a merge
 * tampered with a parasitic quad (violating the global UNION policy) is
 * exported with its PROV-O description, reloaded from {@code provenance.ttl}
 * and detected as inconsistent through the same round-trip.
 */
class InconsistencyDetectionTest {

    @Test
    void tamperedMergeIsDetectedThroughProvenanceRoundTrip(@TempDir Path dir) throws IOException {
        MergePolicy policy = MergePolicy.UNION;

        Set<Quad> initialData = new HashSet<>(Set.of(Vocabulary.quad(
                Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"))));
        Version v0 = new Version("V0", initialData, List.of());

        Set<Quad> branchAData = new HashSet<>(v0.getData());
        branchAData.add(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("price"), Vocabulary.literal("42.0")));
        Version vA = new Version("VA", branchAData, List.of(v0));

        Set<Quad> branchBData = new HashSet<>(v0.getData());
        branchBData.add(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("review1"), Vocabulary.bsbm("reviewFor"), Vocabulary.ex("product1")));
        Version vB = new Version("VB", branchBData, List.of(v0));

        // Tampered merge: a parasitic quad is introduced during the merge,
        // violating the global UNION policy.
        Set<Quad> tamperedData = new HashSet<>(branchAData);
        tamperedData.addAll(branchBData);
        tamperedData.add(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("intruder"), Vocabulary.rdfType(), Vocabulary.bsbm("Product")));
        Version badMerge = new Version("M_BAD", tamperedData, List.of(vA, vB));

        List<Version> versions = List.of(v0, vA, vB, badMerge);
        VersionGraphWriter.writeEachVersionToDirectory(versions, policy, dir);
        ProvOWriter.writeToFile(versions, policy, dir.resolve(ProvOReader.PROVENANCE_FILE));

        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(dir);
        assertFalse(VersionConsistencyChecker.isConsistent(loaded.versions()),
                "the tampered merge must be detected as inconsistent through the PROV-O round-trip");
    }
}
