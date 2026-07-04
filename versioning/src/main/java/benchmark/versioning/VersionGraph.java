package benchmark.versioning;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.jena.sparql.core.Quad;

public class VersionGraph {
    private final Map<String, Version> versions = new HashMap<>();
    /** Policy applied to merges created without an explicit one; may be {@code null}. */
    private final MergePolicy globalPolicy;

    /**
     * @param globalPolicy the policy applied by
     *        {@link #createMerge(String, List)}; {@code null} for graphs
     *        whose merges each carry their own policy — every merge must then
     *        be created with {@link #createMerge(String, List, MergePolicy)}
     */
    public VersionGraph(MergePolicy globalPolicy) {
        this.globalPolicy = globalPolicy;
    }

    /**
     * Case 1: Root node (|pre(v)| = 0). Its state is the initial RDF dataset.
     */
    public Version createRoot(String id, Set<Quad> initialData) {
        if (versions.containsKey(id)) {
            throw new IllegalArgumentException("Version with id " + id + " already exists.");
        }
        Version root = new Version(id, initialData, Collections.emptyList());
        versions.put(id, root);
        return root;
    }

    /**
     * Case 2: Transition node (|pre(v)| = 1). Applies a differential of
     * additions and deletions of quads to the parent's RDF dataset.
     * Several transitions may share the same parent, creating branches.
     */
    public Version createTransition(String id, Version parent, Set<Quad> additions, Set<Quad> deletions) {
        if (versions.containsKey(id)) {
            throw new IllegalArgumentException("Version with id " + id + " already exists.");
        }
        Set<Quad> newData = new HashSet<>(parent.getData());
        newData.removeAll(deletions);
        newData.addAll(additions);
        Version v = new Version(id, newData, Collections.singletonList(parent));
        versions.put(id, v);
        return v;
    }

    /**
     * Case 3: Merge node (|pre(v)| >= 2) under the global policy of this
     * graph. Any number of parents is supported ("octopus" merges).
     */
    public Version createMerge(String id, List<Version> parents) {
        if (globalPolicy == null) {
            throw new IllegalStateException("This graph has no global merge policy:"
                    + " create the merge with an explicit policy");
        }
        return createMerge(id, parents, globalPolicy);
    }

    /**
     * Case 3: Merge node (|pre(v)| >= 2) under an explicit policy. The state
     * is strictly the result of the policy operator applied to the parents'
     * RDF datasets, and the policy is recorded on the version (it ends up in
     * the PROV-O description, so histories mixing several per-merge policies
     * stay verifiable). Any number of parents is supported ("octopus"
     * merges).
     */
    public Version createMerge(String id, List<Version> parents, MergePolicy policy) {
        if (versions.containsKey(id)) {
            throw new IllegalArgumentException("Version with id " + id + " already exists.");
        }
        if (parents.size() < 2) {
            throw new IllegalArgumentException("Merge node must have at least 2 parents.");
        }
        Objects.requireNonNull(policy, "policy");

        List<Set<Quad>> parentsData = parents.stream()
                .map(Version::getData)
                .collect(Collectors.toList());

        Version v = new Version(id, policy.apply(parentsData), parents, policy, null, null);
        versions.put(id, v);
        return v;
    }

    public Collection<Version> getVersions() {
        return Collections.unmodifiableCollection(versions.values());
    }

    /**
     * The global merge policy, or {@code null} when the merges of this graph
     * each carry their own policy ({@link Version#getMergePolicy()}).
     */
    public MergePolicy getGlobalPolicy() {
        return globalPolicy;
    }
}
