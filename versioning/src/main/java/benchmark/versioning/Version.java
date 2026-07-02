package benchmark.versioning;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;

/**
 * A node {@code v ∈ V} of the version DAG: an id, an immutable RDF dataset
 * {@code S(v)} (a set of Apache Jena {@link Quad}s) and the list of its
 * parents {@code pre(v)}.
 */
public class Version {
    private final String id;
    private final Set<Quad> data;
    private final List<Version> parents;

    public Version(String id, Set<Quad> data, List<Version> parents) {
        this.id = id;
        this.data = Collections.unmodifiableSet(new HashSet<>(data));
        this.parents = Collections.unmodifiableList(parents);
    }

    public String getId() {
        return id;
    }

    /**
     * The RDF dataset of this version, represented as a set of Apache Jena
     * quads.
     */
    public Set<Quad> getData() {
        return data;
    }

    /**
     * The RDF dataset of this version, grouped by named graph node.
     * Graph names are independent of version identifiers.
     */
    public Map<Node, Set<Quad>> getNamedGraphs() {
        return data.stream().collect(Collectors.groupingBy(Quad::getGraph, Collectors.toSet()));
    }

    public List<Version> getParents() {
        return parents;
    }

    @Override
    public String toString() {
        return "Version{" +
                "id='" + id + '\'' +
                ", quads=" + data.size() +
                ", parents=" + parents.stream().map(Version::getId).toList() +
                '}';
    }
}
