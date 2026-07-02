package benchmark.versioning;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.jena.sparql.core.Quad;

public class VersionGraph {
    private final Map<String, Version> versions = new HashMap<>();
    private final MergePolicy globalPolicy;

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
     * Case 3: Merge node (|pre(v)| >= 2). The state is strictly the result of
     * the global policy operator applied to the parents' RDF datasets.
     * Any number of parents is supported ("octopus" merges).
     */
    public Version createMerge(String id, List<Version> parents) {
        if (versions.containsKey(id)) {
            throw new IllegalArgumentException("Version with id " + id + " already exists.");
        }
        if (parents.size() < 2) {
            throw new IllegalArgumentException("Merge node must have at least 2 parents.");
        }

        List<Set<Quad>> parentsData = parents.stream()
                .map(Version::getData)
                .collect(Collectors.toList());

        Set<Quad> mergedData = globalPolicy.apply(parentsData);
        Version v = new Version(id, mergedData, parents);
        versions.put(id, v);
        return v;
    }

    public Collection<Version> getVersions() {
        return Collections.unmodifiableCollection(versions.values());
    }

    public MergePolicy getGlobalPolicy() {
        return globalPolicy;
    }
}
