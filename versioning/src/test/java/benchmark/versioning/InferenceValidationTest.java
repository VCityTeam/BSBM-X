package benchmark.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Executable version of the worked micro-examples of
 * {@code Version-history-inference-validation.md} §12: how each global merge
 * policy creates, propagates or repairs invalidity under SHACL (closed
 * world) and RDFS/OWL (open world) rules, classified by the §8 outcome
 * taxonomy of {@link InferenceValidator}.
 */
class InferenceValidationTest {

    private static final String PREFIXES = """
            @prefix sh:   <http://www.w3.org/ns/shacl#> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix bsbm: <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/> .
            @prefix shp:  <http://example.org/shapes/> .
            """;

    /** SHACL: offers bear at least one and at most one price. */
    private static final String OFFER_SHAPES = PREFIXES + """
            shp:OfferShape a sh:NodeShape ;
                sh:targetClass bsbm:Offer ;
                sh:property [ sh:path bsbm:price ; sh:minCount 1 ; sh:maxCount 1 ] .
            """;

    /** SHACL: every review references a declared product (deterministic witness). */
    private static final String REVIEW_SHAPES = PREFIXES + """
            shp:ReviewShape a sh:NodeShape ;
                sh:targetSubjectsOf bsbm:reviewFor ;
                sh:property [ sh:path bsbm:reviewFor ; sh:class bsbm:Product ] .
            """;

    /** SHACL: intrinsic constraints only (single-atom guard, per-triple head). */
    private static final String INTRINSIC_SHAPES = PREFIXES + """
            shp:PriceShape a sh:NodeShape ;
                sh:targetSubjectsOf bsbm:price ;
                sh:property [ sh:path bsbm:price ; sh:datatype xsd:string ;
                              sh:pattern "^[0-9]+\\\\.[0-9]+$" ] .
            """;

    /** OWL: products and reviews are disjoint classes (negative axiom). */
    private static final String DISJOINT_ONTOLOGY = PREFIXES + """
            bsbm:Product a owl:Class .
            bsbm:Review  a owl:Class .
            bsbm:Product owl:disjointWith bsbm:Review .
            """;

    /** RDFS: positive axioms only (domains/ranges) — sees no loss (§6.3). */
    private static final String RDFS_ONTOLOGY = PREFIXES + """
            bsbm:Offer a rdfs:Class .
            bsbm:price a rdf:Property ; rdfs:domain bsbm:Offer ; rdfs:range xsd:string .
            """;

    // --- Worked example A (§12): UNION manufactures a conflict -------------

    @Test
    void unionOfValidParentsViolatesTheUpperBound() {
        VersionGraph graph = divergingPricesGraph(MergePolicy.UNION);
        InferenceValidator.HistoryReport report = validate(OFFER_SHAPES, graph);

        assertTrue(validityOf(report, "V0").valid());
        assertTrue(validityOf(report, "V1").valid());
        assertTrue(validityOf(report, "V2").valid());
        assertFalse(validityOf(report, "M1").valid(),
                "the union merge accumulates two prices and must violate sh:maxCount");
        assertEquals(InferenceValidator.MergeOutcome.EMERGENT_VIOLATION, outcomeOf(report, "M1"));
    }

    // --- Worked example B (§12): INTERSECTION manufactures a loss ---------

    @Test
    void intersectionOfValidParentsLosesTheDivergingWitness() {
        VersionGraph graph = divergingPricesGraph(MergePolicy.INTERSECTION);
        InferenceValidator.HistoryReport report = validate(OFFER_SHAPES, graph);

        assertTrue(validityOf(report, "V1").valid());
        assertTrue(validityOf(report, "V2").valid());
        assertFalse(validityOf(report, "M1").valid(),
                "the intersection keeps the offer but neither branch's price: sh:minCount violated");
        assertEquals(InferenceValidator.MergeOutcome.EMERGENT_VIOLATION, outcomeOf(report, "M1"));
    }

    /**
     * Quad-level variant of example B (§5.1): both branches keep the same
     * type <b>triple</b> but in different <b>named graphs</b>, so the quads
     * differ and the intersection silently loses the witness.
     */
    @Test
    void intersectionDropsATripleStoredInDifferentNamedGraphs() {
        Quad typeInProductsGraph = Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"));
        Quad typeInReviewsGraph = Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"));

        VersionGraph graph = new VersionGraph(MergePolicy.INTERSECTION);
        Version root = graph.createRoot("V0", Set.of(typeInProductsGraph, review()));
        Version a = graph.createTransition("V1", root,
                Set.of(typeInReviewsGraph), Set.of(typeInProductsGraph));
        Version b = graph.createTransition("V2", root, Set.of(), Set.of());
        graph.createMerge("M1", List.of(a, b));

        InferenceValidator.HistoryReport report = validate(REVIEW_SHAPES, graph);
        assertTrue(validityOf(report, "V1").valid(),
                "the branch still asserts the type triple, only in another named graph");
        assertTrue(validityOf(report, "V2").valid());
        assertEquals(InferenceValidator.MergeOutcome.EMERGENT_VIOLATION, outcomeOf(report, "M1"),
                "graph-placement divergence makes the intersection lose the agreed triple");
    }

    // --- Worked example C (§12): SYMMETRIC_DIFFERENCE cancels the core ----

    @Test
    void symmetricDifferenceCancelsTheCommonCore() {
        InferenceValidator.HistoryReport delta =
                validate(REVIEW_SHAPES, coreAndIncrementsGraph(MergePolicy.SYMMETRIC_DIFFERENCE));
        assertTrue(validityOf(delta, "V1").valid());
        assertTrue(validityOf(delta, "V2").valid());
        assertEquals(InferenceValidator.MergeOutcome.EMERGENT_VIOLATION, outcomeOf(delta, "M1"),
                "the product declared in both parents is cancelled, the review added in one survives");

        // Contrast: the same history merged under UNION conforms.
        InferenceValidator.HistoryReport union =
                validate(REVIEW_SHAPES, coreAndIncrementsGraph(MergePolicy.UNION));
        assertEquals(InferenceValidator.MergeOutcome.PRESERVED, outcomeOf(union, "M1"));
    }

    // --- Locality lemma (§7.4): intrinsic constraints are policy-proof ----

    /**
     * A violating instantiation of an intrinsic constraint is a single
     * triple, and every merged triple comes from a valid parent — so the
     * intrinsic family is safe under all three policies, the exemption the
     * incremental validation table of §9 relies on.
     */
    @ParameterizedTest
    @EnumSource(MergePolicy.class)
    void intrinsicConstraintsAreSafeUnderEveryPolicy(MergePolicy policy) {
        VersionGraph graph = new VersionGraph(policy);
        Version root = graph.createRoot("V0", Set.of(productType()));
        Version a = graph.createTransition("V1", root, Set.of(price("offer1", "11.0")), Set.of());
        Version b = graph.createTransition("V2", root, Set.of(price("offer2", "12.0")), Set.of());
        graph.createMerge("M1", List.of(a, b));

        InferenceValidator.HistoryReport report = validate(INTRINSIC_SHAPES, graph);
        assertTrue(report.allValid(),
                () -> "intrinsic constraints must hold everywhere under " + policy);
        assertEquals(InferenceValidator.MergeOutcome.PRESERVED, outcomeOf(report, "M1"));
    }

    // --- Worked example D (§12): repair and inheritance are policy-relative

    @Test
    void intersectionRepairsAndUnionInheritsAnInvalidBranch() {
        InferenceValidator.HistoryReport intersection =
                validate(OFFER_SHAPES, invalidBranchGraph(MergePolicy.INTERSECTION));
        assertFalse(validityOf(intersection, "V1").valid(), "the branch accumulated two prices");
        assertTrue(validityOf(intersection, "V2").valid());
        InferenceValidator.MergeAssessment repaired = intersection.merges().get(0);
        assertEquals(InferenceValidator.MergeOutcome.REPAIRED, repaired.outcome());
        assertEquals(List.of("V1"), repaired.invalidParentIds());

        InferenceValidator.HistoryReport union =
                validate(OFFER_SHAPES, invalidBranchGraph(MergePolicy.UNION));
        assertEquals(InferenceValidator.MergeOutcome.INHERITED_VIOLATION, outcomeOf(union, "M1"));
    }

    // --- Worked example E (§12): open-world conflict detection ------------

    @ParameterizedTest
    @EnumSource(MergePolicy.class)
    void owlDisjointnessUnderEachPolicy(MergePolicy policy) {
        VersionGraph graph = new VersionGraph(policy);
        Version root = graph.createRoot("V0", Set.of());
        Version a = graph.createTransition("V1", root, Set.of(typed("thing", "Product")), Set.of());
        Version b = graph.createTransition("V2", root, Set.of(typed("thing", "Review")), Set.of());
        graph.createMerge("M1", List.of(a, b));

        InferenceValidator.HistoryReport report = validate(DISJOINT_ONTOLOGY, graph);
        assertTrue(validityOf(report, "V1").valid());
        assertTrue(validityOf(report, "V2").valid());

        // Consistency is intersection-safe (§7.2) and broken by union and by
        // symmetric difference, which retains both sides of the conflict (§7.3).
        InferenceValidator.MergeOutcome expected = policy == MergePolicy.INTERSECTION
                ? InferenceValidator.MergeOutcome.PRESERVED
                : InferenceValidator.MergeOutcome.EMERGENT_VIOLATION;
        assertEquals(expected, outcomeOf(report, "M1"),
                () -> "unexpected outcome under " + policy);
    }

    // --- Worked example F (§12): the open world is blind to loss ----------

    @Test
    void rdfsIsBlindToTheLossThatShaclDetects() {
        VersionGraph graph = divergingPricesGraph(MergePolicy.INTERSECTION);

        InferenceValidator.HistoryReport rdfs = validate(RDFS_ONTOLOGY, graph);
        assertTrue(rdfs.allValid(), "positive RDFS axioms cannot flag the lost price (§6.3)");
        assertEquals(InferenceValidator.MergeOutcome.PRESERVED, outcomeOf(rdfs, "M1"));

        InferenceValidator.HistoryReport shacl = validate(OFFER_SHAPES, graph);
        assertFalse(validityOf(shacl, "M1").valid(),
                "the same merge is invalid under the closed-world reading");
    }

    // --- Rule language detection and the example rule files ---------------

    @Test
    void detectsTheRuleLanguageFromTheNamespaces() {
        assertEquals(RuleLanguage.SHACL, RuleLanguage.detect(model(OFFER_SHAPES)));
        assertEquals(RuleLanguage.OWL, RuleLanguage.detect(model(DISJOINT_ONTOLOGY)));
        assertEquals(RuleLanguage.RDFS, RuleLanguage.detect(model(RDFS_ONTOLOGY)));
    }

    @ParameterizedTest
    @EnumSource(MergePolicy.class)
    void exampleRuleFilesValidateAGeneratedHistory(MergePolicy policy) throws IOException {
        VersionGraph graph = VersionGraphGenerator.generate(
                new VersionGraphGenerator.Parameters(12, 3, 2, 20, 6, 42), policy);
        long mergeCount = graph.getVersions().stream().filter(v -> v.getParents().size() >= 2).count();

        for (String file : List.of("shacl-shapes.ttl", "rdfs-ontology.ttl", "owl-ontology.ttl")) {
            InferenceValidator validator =
                    InferenceValidator.fromFile(Path.of("src/main/resources/rules", file));
            InferenceValidator.HistoryReport report = validator.validateHistory(graph.getVersions());
            assertEquals(graph.getVersions().size(), report.versions().size(),
                    () -> file + " must give a verdict for every version");
            assertEquals(mergeCount, report.merges().size(),
                    () -> file + " must classify every merge");
        }

        // The generated content is conflict-free by construction (fresh quads
        // never collide), so both open-world ontologies validate everything —
        // including whatever losses the policy produced (§6.3).
        assertEquals(RuleLanguage.RDFS,
                validatorLanguage("rdfs-ontology.ttl"));
        assertTrue(validateFile("rdfs-ontology.ttl", graph).allValid());
        assertEquals(RuleLanguage.OWL,
                validatorLanguage("owl-ontology.ttl"));
        assertTrue(validateFile("owl-ontology.ttl", graph).allValid());
        assertEquals(RuleLanguage.SHACL,
                validatorLanguage("shacl-shapes.ttl"));
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Root offer with one price; each branch replaces it with a different
     * value. Both branches conform to {@link #OFFER_SHAPES}; the merge
     * violates sh:maxCount under UNION and sh:minCount under INTERSECTION.
     */
    private static VersionGraph divergingPricesGraph(MergePolicy policy) {
        VersionGraph graph = new VersionGraph(policy);
        Version root = graph.createRoot("V0", Set.of(offerType(), price("10.0")));
        Version a = graph.createTransition("V1", root, Set.of(price("11.0")), Set.of(price("10.0")));
        Version b = graph.createTransition("V2", root, Set.of(price("12.0")), Set.of(price("10.0")));
        graph.createMerge("M1", List.of(a, b));
        return graph;
    }

    /**
     * The product (common core) is in both branches; one branch adds a
     * review referencing it, the other an unrelated offer.
     */
    private static VersionGraph coreAndIncrementsGraph(MergePolicy policy) {
        VersionGraph graph = new VersionGraph(policy);
        Version root = graph.createRoot("V0", Set.of(productType()));
        Version a = graph.createTransition("V1", root, Set.of(review()), Set.of());
        Version b = graph.createTransition("V2", root, Set.of(offerType()), Set.of());
        graph.createMerge("M1", List.of(a, b));
        return graph;
    }

    /** Branch V1 accumulates a second price (invalid); branch V2 stays valid. */
    private static VersionGraph invalidBranchGraph(MergePolicy policy) {
        VersionGraph graph = new VersionGraph(policy);
        Version root = graph.createRoot("V0", Set.of(offerType(), price("10.0")));
        Version a = graph.createTransition("V1", root, Set.of(price("11.0")), Set.of());
        Version b = graph.createTransition("V2", root, Set.of(productType()), Set.of());
        graph.createMerge("M1", List.of(a, b));
        return graph;
    }

    private static InferenceValidator.HistoryReport validate(String rulesTurtle, VersionGraph graph) {
        return InferenceValidator.forRules(model(rulesTurtle)).validateHistory(graph.getVersions());
    }

    private static InferenceValidator.HistoryReport validateFile(String file, VersionGraph graph)
            throws IOException {
        return InferenceValidator.fromFile(Path.of("src/main/resources/rules", file))
                .validateHistory(graph.getVersions());
    }

    private static RuleLanguage validatorLanguage(String file) throws IOException {
        return InferenceValidator.fromFile(Path.of("src/main/resources/rules", file)).getLanguage();
    }

    private static InferenceValidator.VersionValidity validityOf(
            InferenceValidator.HistoryReport report, String versionId) {
        return report.versions().stream()
                .filter(v -> v.versionId().equals(versionId))
                .findFirst().orElseThrow();
    }

    private static InferenceValidator.MergeOutcome outcomeOf(
            InferenceValidator.HistoryReport report, String mergeId) {
        return report.merges().stream()
                .filter(m -> m.mergeId().equals(mergeId))
                .findFirst().orElseThrow()
                .outcome();
    }

    private static Model model(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.create().fromString(turtle).lang(Lang.TURTLE).parse(model.getGraph());
        return model;
    }

    private static Quad offerType() {
        return typed("offer1", "Offer");
    }

    private static Quad productType() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"));
    }

    private static Quad typed(String local, String className) {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex(local), Vocabulary.rdfType(), Vocabulary.bsbm(className));
    }

    private static Quad price(String value) {
        return price("offer1", value);
    }

    private static Quad price(String offerLocal, String value) {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex(offerLocal), Vocabulary.bsbm("price"), Vocabulary.literal(value));
    }

    private static Quad review() {
        return Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("review1"), Vocabulary.bsbm("reviewFor"), Vocabulary.ex("product1"));
    }
}
