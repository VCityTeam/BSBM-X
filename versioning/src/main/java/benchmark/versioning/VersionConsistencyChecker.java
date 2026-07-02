package benchmark.versioning;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.jena.sparql.core.Quad;

public class VersionConsistencyChecker {
    public static boolean isConsistent(VersionGraph graph) {
        return isConsistent(graph.getVersions(), graph.getGlobalPolicy());
    }

    /**
     * Checks that every merge node (|pre(v)| >= 2) of the given versions has
     * a state strictly equal to the global policy operator applied to the
     * RDF datasets of its parents.
     */
    public static boolean isConsistent(Collection<Version> versions, MergePolicy policy) {
        for (Version v : versions) {
            List<Version> parents = v.getParents();
            if (parents.size() >= 2) {
                List<Set<Quad>> parentsData = parents.stream()
                        .map(Version::getData)
                        .collect(Collectors.toList());
                Set<Quad> expectedData = policy.apply(parentsData);
                if (!v.getData().equals(expectedData)) {
                    System.out.println("[DEBUG_LOG] Consistency violation at version: " + v.getId());
                    System.out.println("[DEBUG_LOG] Expected: " + expectedData);
                    System.out.println("[DEBUG_LOG] Actual:   " + v.getData());
                    return false;
                }
            }
        }
        return true;
    }
}
