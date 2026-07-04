package benchmark.versioning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.jena.sparql.core.Quad;

public class VersionConsistencyChecker {

    /**
     * Checks that every merge node (|pre(v)| >= 2) of the given versions has
     * a state strictly equal to <b>its own merge policy</b> operator
     * ({@link Version#getMergePolicy()}) applied to the RDF datasets of its
     * parents, and that the PROV-O lifecycle instants (when present) obey the
     * generation/invalidation rules (see {@link #hasConsistentTimestamps}).
     * <p>
     * The policy of each merge comes from the version itself — for a history
     * reloaded with {@link ProvOReader}, from the {@code provenance.ttl}
     * description — so histories mixing several per-merge policies are
     * checked merge by merge. A merge version carrying no policy cannot be
     * verified and is reported as inconsistent.
     */
    public static boolean isConsistent(Collection<Version> versions) {
        for (Version v : versions) {
            List<Version> parents = v.getParents();
            if (parents.size() >= 2) {
                MergePolicy policy = v.getMergePolicy();
                if (policy == null) {
                    System.out.println("[DEBUG_LOG] Merge version " + v.getId()
                            + " carries no merge policy: cannot verify its state");
                    return false;
                }
                List<Set<Quad>> parentsData = parents.stream()
                        .map(Version::getData)
                        .collect(Collectors.toList());
                Set<Quad> expectedData = policy.apply(parentsData);
                if (!v.getData().equals(expectedData)) {
                    System.out.println("[DEBUG_LOG] Consistency violation at version: " + v.getId()
                            + " (policy " + policy + ")");
                    System.out.println("[DEBUG_LOG] Expected: " + expectedData);
                    System.out.println("[DEBUG_LOG] Actual:   " + v.getData());
                    return false;
                }
            }
        }
        return hasConsistentTimestamps(versions);
    }

    /**
     * Checks the PROV-O lifecycle instants of the versions:
     * <ul>
     *   <li>every version is generated strictly after each of its
     *       parents;</li>
     *   <li>all versions following the same version are generated at the
     *       same instant (mandatory after a fork, otherwise the fork would
     *       need several invalidation instants);</li>
     *   <li>a superseded version is invalidated exactly when its following
     *       versions are generated;</li>
     *   <li>final versions are still valid: they carry no invalidation
     *       instant.</li>
     * </ul>
     * A history without any timestamp (e.g. reloaded from an older
     * provenance file) is accepted; a partially timestamped one is not.
     */
    public static boolean hasConsistentTimestamps(Collection<Version> versions) {
        long timestamped = versions.stream().filter(v -> v.getGeneratedAtTime() != null).count();
        if (timestamped == 0) {
            return true;
        }
        if (timestamped < versions.size()) {
            System.out.println("[DEBUG_LOG] Only " + timestamped + " of " + versions.size()
                    + " versions carry a prov:generatedAtTime");
            return false;
        }

        Set<Version> all = new HashSet<>(versions);
        Map<Version, List<Version>> followers = new HashMap<>();
        for (Version v : all) {
            for (Version parent : v.getParents()) {
                if (!all.contains(parent)) {
                    continue;
                }
                followers.computeIfAbsent(parent, k -> new ArrayList<>()).add(v);
                if (!v.getGeneratedAtTime().isAfter(parent.getGeneratedAtTime())) {
                    System.out.println("[DEBUG_LOG] Version " + v.getId() + " ("
                            + v.getGeneratedAtTime() + ") is not generated strictly after its parent "
                            + parent.getId() + " (" + parent.getGeneratedAtTime() + ")");
                    return false;
                }
            }
        }
        for (Version v : all) {
            List<Version> following = followers.get(v);
            if (following == null) {
                if (v.getInvalidatedAtTime() != null) {
                    System.out.println("[DEBUG_LOG] Final version " + v.getId()
                            + " must still be valid but is invalidated at " + v.getInvalidatedAtTime());
                    return false;
                }
                continue;
            }
            Instant followersGeneration = following.get(0).getGeneratedAtTime();
            for (Version follower : following) {
                if (!follower.getGeneratedAtTime().equals(followersGeneration)) {
                    System.out.println("[DEBUG_LOG] Versions following " + v.getId()
                            + " are not all generated at the same instant: " + follower.getId()
                            + " (" + follower.getGeneratedAtTime() + ") vs " + following.get(0).getId()
                            + " (" + followersGeneration + ")");
                    return false;
                }
            }
            if (!followersGeneration.equals(v.getInvalidatedAtTime())) {
                System.out.println("[DEBUG_LOG] Version " + v.getId() + " must be invalidated at "
                        + followersGeneration + " (when its followers are generated) but is invalidated at "
                        + v.getInvalidatedAtTime());
                return false;
            }
        }
        return true;
    }
}
