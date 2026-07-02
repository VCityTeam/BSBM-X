package benchmark.versioning;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;

/**
 * Central definition of the RDF vocabulary used by the generated version
 * content, and the single place where domain terms are turned into Apache
 * Jena {@link Node}s and {@link Quad}s.
 * <p>
 * Every quad handled by this module is an Apache Jena {@link Quad}
 * ({@code (graph, subject, predicate, object)} of {@link Node}s), and Jena is
 * responsible for all quad/triple parsing and serialization. The
 * {@code ex:}, {@code bsbm:} and {@code rdf:} prefixes used throughout are
 * expanded to full IRIs here, so the prefix-to-IRI mapping is defined once.
 */
public final class Vocabulary {

    /** Namespace of the {@code rdf:} prefix. */
    public static final String RDF_NS = RDF.getURI();
    /** Namespace of the {@code ex:} prefix (example instances). */
    public static final String EX_NS = "http://example.org/";
    /** Namespace of the {@code bsbm:} prefix (BSBM vocabulary). */
    public static final String BSBM_NS = "http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/";

    /** Named graph holding the product triples. */
    public static final String GRAPH_PRODUCTS = "http://example.org/graph/products";
    /** Named graph holding the offer triples. */
    public static final String GRAPH_OFFERS = "http://example.org/graph/offers";
    /** Named graph holding the review triples. */
    public static final String GRAPH_REVIEWS = "http://example.org/graph/reviews";

    private Vocabulary() {
        // utility class
    }

    /** An IRI node in the {@code ex:} namespace: {@code ex:<local>}. */
    public static Node ex(String local) {
        return NodeFactory.createURI(EX_NS + local);
    }

    /** An IRI node in the {@code bsbm:} namespace: {@code bsbm:<local>}. */
    public static Node bsbm(String local) {
        return NodeFactory.createURI(BSBM_NS + local);
    }

    /** An IRI node from a full IRI (used for the named graphs). */
    public static Node iri(String iri) {
        return NodeFactory.createURI(iri);
    }

    /** The {@code rdf:type} predicate node. */
    public static Node rdfType() {
        return RDF.type.asNode();
    }

    /** A plain (xsd:string) literal node. */
    public static Node literal(String lexicalForm) {
        return NodeFactory.createLiteralString(lexicalForm);
    }

    /** Builds a quad {@code (graph, subject, predicate, object)}. */
    public static Quad quad(Node graph, Node subject, Node predicate, Node object) {
        return new Quad(graph, subject, predicate, object);
    }
}
