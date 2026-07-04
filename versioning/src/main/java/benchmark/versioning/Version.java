package benchmark.versioning;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.jena.sparql.core.Quad;

/**
 * A node {@code v ∈ V} of the version DAG: an id, an immutable RDF dataset
 * {@code S(v)} (a set of Apache Jena {@link Quad}s) and the list of its
 * parents {@code pre(v)}. Merge nodes (two parents or more) also record the
 * {@link MergePolicy} that produced their state, so histories mixing several
 * per-merge policies stay verifiable (see {@link VersionConsistencyChecker}).
 * <p>
 * A version also carries its PROV-O lifecycle instants:
 * <ul>
 *   <li>{@code prov:generatedAtTime} — when the version was generated;</li>
 *   <li>{@code prov:invalidatedAtTime} — when its following versions were
 *       generated, superseding it. Final versions (no followers) are still
 *       valid and keep {@code null} here.</li>
 * </ul>
 * The instants are assigned after the DAG is built (see
 * {@link VersionTimestamps}), because the invalidation of a version and the
 * generation-time alignment of the versions following a fork depend on
 * children that do not exist yet when the version is created.
 */
public class Version {
    private final String id;
    private final Set<Quad> data;
    private final List<Version> parents;
    /** Policy that produced this merge node; {@code null} for roots and transitions. */
    private final MergePolicy mergePolicy;
    private Instant generatedAtTime;
    private Instant invalidatedAtTime;

    public Version(String id, Set<Quad> data, List<Version> parents) {
        this(id, data, parents, null, null, null);
    }

    public Version(String id, Set<Quad> data, List<Version> parents,
                   Instant generatedAtTime, Instant invalidatedAtTime) {
        this(id, data, parents, null, generatedAtTime, invalidatedAtTime);
    }

    public Version(String id, Set<Quad> data, List<Version> parents, MergePolicy mergePolicy,
                   Instant generatedAtTime, Instant invalidatedAtTime) {
        this.id = id;
        this.data = Set.copyOf(data);
        this.parents = Collections.unmodifiableList(parents);
        this.mergePolicy = mergePolicy;
        this.generatedAtTime = generatedAtTime;
        this.invalidatedAtTime = invalidatedAtTime;
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

    public List<Version> getParents() {
        return parents;
    }

    /**
     * The merge policy that produced this version's state from its parents,
     * recorded per merge in the PROV-O description (see {@link ProvOWriter}).
     * {@code null} for roots and transitions.
     */
    public MergePolicy getMergePolicy() {
        return mergePolicy;
    }

    /**
     * The {@code prov:generatedAtTime} of this version: when it was
     * generated. {@code null} until assigned by {@link VersionTimestamps}.
     */
    public Instant getGeneratedAtTime() {
        return generatedAtTime;
    }

    /**
     * The {@code prov:invalidatedAtTime} of this version: the instant its
     * following versions were generated. {@code null} for final versions,
     * which are still valid.
     */
    public Instant getInvalidatedAtTime() {
        return invalidatedAtTime;
    }

    void setGeneratedAtTime(Instant generatedAtTime) {
        this.generatedAtTime = generatedAtTime;
    }

    void setInvalidatedAtTime(Instant invalidatedAtTime) {
        this.invalidatedAtTime = invalidatedAtTime;
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
