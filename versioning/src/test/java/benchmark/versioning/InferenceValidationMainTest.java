package benchmark.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link InferenceValidationMain} command-line program: the
 * {@code --policy} parameter (selecting the history to test by its global
 * merge policy, read from {@code provenance.ttl}), the per-version
 * inferred-knowledge files {@code <id>-<rdfs|owl>-infered.nq} it
 * materializes, and its exit codes.
 */
class InferenceValidationMainTest {

    private static final Path RULES_DIR = Path.of("src/main/resources/rules");

    @TempDir
    Path tmp;

    // --- The --policy parameter --------------------------------------------

    @Test
    void parsePolicyAcceptsAnyCaseAndSeparator() {
        assertEquals(MergePolicy.UNION, InferenceValidationMain.parsePolicy("union"));
        assertEquals(MergePolicy.INTERSECTION, InferenceValidationMain.parsePolicy("Intersection"));
        assertEquals(MergePolicy.SYMMETRIC_DIFFERENCE,
                InferenceValidationMain.parsePolicy("symmetric-difference"));
        assertEquals(MergePolicy.SYMMETRIC_DIFFERENCE,
                InferenceValidationMain.parsePolicy("SYMMETRIC_DIFFERENCE"));
        assertThrows(IllegalArgumentException.class,
                () -> InferenceValidationMain.parsePolicy("majority-vote"));
    }

    @Test
    void policyParameterSelectsOnlyTheMatchingHistory() throws IOException {
        VersionGraph union = exportHistory(tmp.resolve("union"), MergePolicy.UNION);
        exportHistory(tmp.resolve("intersection"), MergePolicy.INTERSECTION);

        int exit = InferenceValidationMain.run(new String[] {
                "--rules", RULES_DIR.resolve("rdfs-ontology.ttl").toString(),
                "--dir", tmp.toString(),
                "--policy", "union"});

        assertEquals(0, exit, "the all-positive RDFS ontology validates every version");
        assertEquals(union.getVersions().size(), inferredFilesIn(tmp.resolve("union"), "rdfs").size(),
                "one inferred file per version of the selected history");
        assertTrue(inferredFilesIn(tmp.resolve("intersection"), "rdfs").isEmpty(),
                "the history filtered out by --policy must not be touched");
    }

    @Test
    void policyMatchingNoExportedHistoryIsAUsageError() throws IOException {
        exportHistory(tmp.resolve("union"), MergePolicy.UNION);

        int exit = InferenceValidationMain.run(new String[] {
                "--rules", RULES_DIR.resolve("rdfs-ontology.ttl").toString(),
                "--dir", tmp.toString(),
                "--policy", "symmetric-difference"});

        assertEquals(2, exit);
    }

    // --- The inferred-knowledge files ---------------------------------------

    @Test
    void owlRulesMaterializeOwlInferredFilesForEveryVersion() throws IOException {
        VersionGraph graph = exportHistory(tmp, MergePolicy.UNION);

        int exit = InferenceValidationMain.run(new String[] {
                "--rules", RULES_DIR.resolve("owl-ontology.ttl").toString(),
                "--dir", tmp.toString()});

        assertEquals(0, exit, "the generated content is conflict-free under the OWL ontology");
        assertEquals(graph.getVersions().size(), inferredFilesIn(tmp, "owl").size());
    }

    @Test
    void shaclRulesWriteNoInferredFiles() throws IOException {
        exportHistory(tmp, MergePolicy.UNION);

        int exit = InferenceValidationMain.run(new String[] {
                "--rules", RULES_DIR.resolve("shacl-shapes.ttl").toString(),
                "--dir", tmp.toString()});

        assertTrue(exit == 0 || exit == 1, "a SHACL run must complete (verdict-dependent exit)");
        try (Stream<Path> children = Files.list(tmp)) {
            assertTrue(children.noneMatch(p -> p.getFileName().toString().endsWith("-infered.nq")),
                    "the SHACL constraint regime entails nothing, so no inferred files");
        }
    }

    // --- The metagraph rules (--metagraph-rules) -----------------------------

    @Test
    void metagraphRulesReproduceTheEngineMergeOutcomes() throws IOException {
        VersionGraph graph = exportHistory(tmp, MergePolicy.UNION);
        InferenceValidator validator =
                InferenceValidator.fromFile(RULES_DIR.resolve("shacl-shapes.ttl"));
        List<Rule> metagraphRules =
                InferenceValidator.loadMetagraphRules(RULES_DIR.resolve("metagraph.rules"));

        InferenceValidator.MetagraphReport meta =
                validator.inferMetagraph(graph.getVersions(), MergePolicy.UNION, metagraphRules);

        assertFalse(meta.merges().isEmpty(), "the generated history has a merge");
        assertTrue(meta.allAgree(),
                "the metagraph rules must re-derive the engine's outcome for every merge: "
                        + meta.merges());
    }

    @Test
    void metagraphOptionWritesTheInferredMetagraphFile() throws IOException {
        exportHistory(tmp, MergePolicy.UNION);

        int exit = InferenceValidationMain.run(new String[] {
                "--rules", RULES_DIR.resolve("shacl-shapes.ttl").toString(),
                "--dir", tmp.toString(),
                "--metagraph-rules", RULES_DIR.resolve("metagraph.rules").toString()});

        assertTrue(exit == 0 || exit == 1, "the run must complete (verdict-dependent exit)");
        Path file = tmp.resolve("metagraph-shacl-infered.ttl");
        assertTrue(Files.isRegularFile(file), "metagraph-shacl-infered.ttl must be written");
        Model derived = RDFDataMgr.loadModel(file.toUri().toString());
        Property outcome = derived.createProperty(InferenceValidator.META_NS + "outcome");
        assertTrue(derived.listStatements(null, outcome, (RDFNode) null).hasNext(),
                "the derived metagraph must classify the merge");
    }

    // --- Usage errors -------------------------------------------------------

    @Test
    void usageErrorsExitWithCode2() throws IOException {
        assertEquals(2, InferenceValidationMain.run(new String[] {}),
                "--rules is required");
        assertEquals(2, InferenceValidationMain.run(new String[] {"--rules"}),
                "an option without its value");
        assertEquals(2, InferenceValidationMain.run(new String[] {
                        "--rules", "rules.ttl", "--policy", "majority-vote"}),
                "an invalid --policy value");
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Generates a small parametric history under the given policy and exports
     * it (per-version N-Quads files plus the PROV-O description) into the
     * given directory, like {@code Main} does.
     */
    private static VersionGraph exportHistory(Path directory, MergePolicy policy) throws IOException {
        VersionGraph graph = VersionGraphGenerator.generate(
                new VersionGraphGenerator.Parameters(6, 2, 1, 8, 4, 42), policy);
        VersionGraphWriter.writeEachVersionToDirectory(graph, directory);
        ProvOWriter.writeToFile(graph, directory.resolve(ProvOReader.PROVENANCE_FILE));
        return graph;
    }

    private static List<Path> inferredFilesIn(Path directory, String ruleType) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .filter(p -> p.getFileName().toString().endsWith("-" + ruleType + "-infered.nq"))
                    .sorted()
                    .toList();
        }
    }
}
