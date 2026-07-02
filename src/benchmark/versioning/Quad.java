package benchmark.versioning;

import java.util.Objects;

/**
 * An immutable RDF quad: (subject, predicate, object, graphName).
 * <p>
 * The graph name identifies the named graph the triple belongs to inside the
 * RDF dataset of a version. It is completely independent of the version
 * identifiers of the version graph: several versions may contain quads of the
 * same named graph, and a single version may contain quads spread over
 * several named graphs.
 */
public final class Quad {
    private final String subject;
    private final String predicate;
    private final String object;
    private final String graphName;

    public Quad(String subject, String predicate, String object, String graphName) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        this.object = Objects.requireNonNull(object, "object");
        this.graphName = Objects.requireNonNull(graphName, "graphName");
    }

    public String getSubject() {
        return subject;
    }

    public String getPredicate() {
        return predicate;
    }

    public String getObject() {
        return object;
    }

    public String getGraphName() {
        return graphName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quad other)) return false;
        return subject.equals(other.subject)
                && predicate.equals(other.predicate)
                && object.equals(other.object)
                && graphName.equals(other.graphName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, predicate, object, graphName);
    }

    @Override
    public String toString() {
        return "<" + subject + " " + predicate + " " + object + " " + graphName + ">";
    }
}
