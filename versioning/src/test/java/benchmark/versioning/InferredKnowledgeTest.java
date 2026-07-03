package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The <b>inferred knowledge</b> of a version ({@link
 * InferenceValidator#inferredKnowledge}) and its per-version export files
 * {@code <id>-<rdfs|owl>-infered.nq} ({@link
 * InferenceValidator#writeInferredFiles}): what an RDFS or OWL rule set
 * entails from a version's data beyond what is asserted, how the merge
 * policy shapes it, and why the SHACL constraint regime has none.
 */
class InferredKnowledgeTest {

    private static final String PREFIXES = """
            @prefix sh:   <http://www.w3.org/ns/shacl#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix bsbm: <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/> .
            @prefix shp:  <http://example.org/shapes/> .
            """;

    /** RDFS: domains and ranges — property use entails the types of its ends. */
    private static final String RDFS_ONTOLOGY = PREFIXES + """
            bsbm:Product a rdfs:Class .
            bsbm:Offer   a rdfs:Class .
            bsbm:Review  a rdfs:Class .
            bsbm:reviewFor a rdf:Property ; rdfs:domain bsbm:Review ; rdfs:range bsbm:Product .
            bsbm:price     a rdf:Property ; rdfs:domain bsbm:Offer .
            """;

    /** OWL: a functional producer — two values entail owl:sameAs (non-UNA, §6.3). */
    private static final String FUNCTIONAL_ONTOLOGY = PREFIXES + """
            bsbm:producer a owl:ObjectProperty, owl:FunctionalProperty .
            """;

    /** SHACL: a constraint regime — validates, entails nothing. */
    private static final String OFFER_SHAPES = PREFIXES + """
            shp:OfferShape a sh:NodeShape ;
                sh:targetClass bsbm:Offer ;
                sh:property [ sh:path bsbm:price ; sh:minCount 1 ] .
            """;

    // --- What is inferred --------------------------------------------------

    @Test
    void rdfsInfersTypesFromDomainsAndRanges() {
        Version version = new Version("V0", Set.of(review()), List.of());
        Set<Quad> inferred = validator(RDFS_ONTOLOGY).inferredKnowledge(version);

        assertTrue(inferred.contains(inferredQuad(
                        Vocabulary.ex("review1"), Vocabulary.rdfType(), Vocabulary.bsbm("Review"))),
                "the domain of bsbm:reviewFor types the review");
        assertTrue(inferred.contains(inferredQuad(
                        Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"))),
                "the range of bsbm:reviewFor types the product");
    }

    @Test
    void inferredKnowledgeExcludesTheAssertedDataAndTheSchemaOnlyEntailments() {
        Version version = new Version("V0", Set.of(review()), List.of());
        Set<Quad> inferred = validator(RDFS_ONTOLOGY).inferredKnowledge(version);

        for (Quad quad : inferred) {
            assertEquals(Vocabulary.GRAPH_INFERRED, quad.getGraph().getURI(),
                    "every inferred statement lives in the dedicated named graph");
            assertNotEquals(quad.asTriple(), review().asTriple(), "asserted statements are not repeated");
            assertNotEquals(quad.getSubject(), Vocabulary.bsbm("reviewFor"), "statements entailed by the ontology alone are subtracted");
        }
    }

    @Test
    void owlFunctionalPropertyEntailsSameAs() {
        Quad producerA = Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("producer"), Vocabulary.ex("acme"));
        Quad producerB = Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("producer"), Vocabulary.ex("acmeLtd"));
        Version version = new Version("V0", Set.of(producerA, producerB), List.of());

        InferenceValidator validator = validator(FUNCTIONAL_ONTOLOGY);
        assertEquals(RuleLanguage.OWL, validator.getLanguage());
        Set<Quad> inferred = validator.inferredKnowledge(version);

        Node sameAs = Vocabulary.iri(RuleLanguage.OWL_NS + "sameAs");
        assertTrue(inferred.contains(inferredQuad(Vocabulary.ex("acme"), sameAs, Vocabulary.ex("acmeLtd")))
                        || inferred.contains(inferredQuad(Vocabulary.ex("acmeLtd"), sameAs, Vocabulary.ex("acme"))),
                "two values of a functional property entail owl:sameAs (no unique-name assumption)");
    }

    // --- How the merge policy shapes the inferred knowledge -----------------

    @Test
    void unionMergeAccumulatesBothBranchesInferences() {
        VersionGraph graph = reviewAndOfferBranches(MergePolicy.UNION);
        Set<Quad> inferred = validator(RDFS_ONTOLOGY).inferredKnowledge(versionOf(graph, "M1"));

        assertTrue(inferred.contains(inferredQuad(
                Vocabulary.ex("review1"), Vocabulary.rdfType(), Vocabulary.bsbm("Review"))));
        assertTrue(inferred.contains(inferredQuad(
                Vocabulary.ex("offer1"), Vocabulary.rdfType(), Vocabulary.bsbm("Offer"))));
    }

    @Test
    void intersectionMergeLosesBothBranchesInferences() {
        VersionGraph graph = reviewAndOfferBranches(MergePolicy.INTERSECTION);
        Set<Quad> inferred = validator(RDFS_ONTOLOGY).inferredKnowledge(versionOf(graph, "M1"));

        assertFalse(inferred.contains(inferredQuad(
                        Vocabulary.ex("review1"), Vocabulary.rdfType(), Vocabulary.bsbm("Review"))),
                "the review only exists in one branch, so the intersection loses its inference");
        assertFalse(inferred.contains(inferredQuad(
                Vocabulary.ex("offer1"), Vocabulary.rdfType(), Vocabulary.bsbm("Offer"))));
    }

    // --- SHACL entails nothing ---------------------------------------------

    @Test
    void shaclConstraintRegimeEntailsNothing() {
        InferenceValidator validator = validator(OFFER_SHAPES);
        assertEquals(RuleLanguage.SHACL, validator.getLanguage());
        assertFalse(validator.supportsInference());

        Version version = new Version("V0", Set.of(review()), List.of());
        assertThrows(IllegalStateException.class, () -> validator.inferredKnowledge(version));
        assertThrows(IllegalStateException.class,
                () -> validator.writeInferredFiles(List.of(version), Path.of("unused")));
    }

    // --- The per-version export files ---------------------------------------

    @Test
    void writesOneValidNQuadsInferredFilePerVersion(@TempDir Path tmp) throws IOException {
        VersionGraph graph = reviewAndOfferBranches(MergePolicy.UNION);
        InferenceValidator validator = validator(RDFS_ONTOLOGY);

        List<Path> files = validator.writeInferredFiles(graph.getVersions(), tmp);

        assertEquals(graph.getVersions().size(), files.size());
        assertEquals("V0-rdfs-infered.nq", files.get(0).getFileName().toString(),
                "files are named <id>-<ruletype>-infered.nq, in topological order");
        for (Version v : graph.getVersions()) {
            Path file = tmp.resolve(validator.inferredFileNameOf(v.getId()));
            assertTrue(Files.isRegularFile(file), "missing " + file);
            assertEquals(validator.inferredKnowledge(v), parseNQuads(file),
                    "the file holds exactly the inferred knowledge of " + v.getId());
        }
    }

    @Test
    void inferredFilesDoNotDisturbTheProvenanceRoundTrip(@TempDir Path tmp) throws IOException {
        VersionGraph graph = reviewAndOfferBranches(MergePolicy.UNION);
        VersionGraphWriter.writeEachVersionToDirectory(graph, tmp);
        ProvOWriter.writeToFile(graph, tmp.resolve(ProvOReader.PROVENANCE_FILE));

        validator(RDFS_ONTOLOGY).writeInferredFiles(graph.getVersions(), tmp);

        ProvOReader.ProvenanceGraph reloaded = ProvOReader.read(tmp);
        assertEquals(graph.getVersions().size(), reloaded.versions().size());
        for (Version v : graph.getVersions()) {
            Version r = reloaded.versions().stream()
                    .filter(x -> x.getId().equals(v.getId()))
                    .findFirst().orElseThrow();
            assertEquals(v.getData(), r.getData(),
                    "the inferred files must not leak into the reloaded datasets");
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Root declares the product; branch V1 adds a review of it, branch V2 an
     * offer with a price; M1 merges the branches under the given policy.
     */
    private static VersionGraph reviewAndOfferBranches(MergePolicy policy) {
        VersionGraph graph = new VersionGraph(policy);
        Version root = graph.createRoot("V0", Set.of(productType()));
        Version a = graph.createTransition("V1", root, Set.of(review()), Set.of());
        Version b = graph.createTransition("V2", root, Set.of(price()), Set.of());
        graph.createMerge("M1", List.of(a, b));
        return graph;
    }

    private static Version versionOf(VersionGraph graph, String id) {
        return graph.getVersions().stream()
                .filter(v -> v.getId().equals(id))
                .findFirst().orElseThrow();
    }

    private static InferenceValidator validator(String rulesTurtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.create().fromString(rulesTurtle).lang(Lang.TURTLE).parse(model.getGraph());
        return InferenceValidator.forRules(model);
    }

    private static Set<Quad> parseNQuads(Path file) {
        DatasetGraph dsg = DatasetGraphFactory.createGeneral();
        RDFDataMgr.read(dsg, file.toUri().toString(), Lang.NQUADS);
        Set<Quad> quads = new HashSet<>();
        dsg.find().forEachRemaining(quads::add);
        return quads;
    }

    private static Quad inferredQuad(Node subject, Node predicate, Node object) {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_INFERRED), subject, predicate, object);
    }

    private static Quad productType() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"));
    }

    private static Quad review() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("review1"), Vocabulary.bsbm("reviewFor"), Vocabulary.ex("product1"));
    }

    private static Quad price() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("price"), Vocabulary.literal("42.0"));
    }
}
