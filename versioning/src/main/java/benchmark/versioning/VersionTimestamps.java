package benchmark.versioning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns the PROV-O lifecycle instants of a version DAG:
 * {@code prov:generatedAtTime} and {@code prov:invalidatedAtTime}
 * (see {@link Version}).
 * <p>
 * The assignment follows four rules:
 * <ol>
 *   <li>a version is generated at the time it was programmatically
 *       generated — the instants are anchored at the wall-clock instant the
 *       generation ran ({@code base}) and each generation step advances the
 *       clock by {@code step};</li>
 *   <li>a version is invalidated at the instant its following versions are
 *       generated;</li>
 *   <li>all versions following a fork node are generated at the same
 *       instant — mandatory, otherwise rule 2 would need several
 *       invalidation instants for the fork node, which OWL-time/PROV
 *       forbids;</li>
 *   <li>final versions (no followers) are still valid: they carry no
 *       {@code prov:invalidatedAtTime}.</li>
 * </ol>
 * Additionally every version is generated <b>strictly after</b> each of its
 * parents, as PROV derivations require.
 * <p>
 * Rules 2–3 mean all children of a version share one generation instant, so
 * the children of each version are merged into "same instant" classes
 * (transitively: a merge node that follows two fork nodes ties the two
 * children sets together). The DAG contracted over those classes must stay
 * acyclic for a strictly-increasing assignment to exist; each class then
 * gets {@code base + level(class) × step}, where {@code level} is its
 * longest-path depth in the contracted DAG. Not every DAG is schedulable —
 * e.g. a merge of a version {@code F} with one of {@code F}'s own children
 * makes the rules contradictory — which is why
 * {@link VersionGraphGenerator} checks {@link #canAddMerge} before creating
 * a merge node.
 */
final class VersionTimestamps {

    private VersionTimestamps() {
        // utility class
    }

    /**
     * Assigns {@code generatedAtTime} and {@code invalidatedAtTime} to every
     * version of the DAG, anchored at {@code base} and advancing by
     * {@code step} per generation level.
     *
     * @throws IllegalStateException if the DAG admits no assignment
     *         satisfying the rules (see class comment)
     */
    static void assign(Collection<Version> versions, Instant base, Duration step) {
        Map<Version, Integer> levels = levelsOrNull(versions);
        if (levels == null) {
            throw new IllegalStateException("The version DAG admits no generation-time assignment: "
                    + "the versions following some fork cannot all be generated at the same instant");
        }
        for (Version v : versions) {
            v.setGeneratedAtTime(base.plus(step.multipliedBy(levels.get(v))));
        }
        Map<Version, List<Version>> children = childrenOf(versions);
        for (Version v : versions) {
            List<Version> followers = children.get(v);
            // Rule 2 for superseded versions, rule 4 (null) for final ones;
            // all followers share one generation instant by construction.
            v.setInvalidatedAtTime(followers == null ? null : followers.get(0).getGeneratedAtTime());
        }
    }

    /**
     * Whether a merge node with the given parents can be added to the DAG
     * while keeping it schedulable. Transitions never break schedulability
     * (a fresh leaf with a single parent adds no equality between existing
     * versions), so only merges need this check.
     */
    static boolean canAddMerge(Collection<Version> versions, List<Version> parents) {
        List<Version> withCandidate = new ArrayList<>(versions);
        withCandidate.add(new Version("candidate", Set.of(), List.copyOf(parents)));
        return levelsOrNull(withCandidate) != null;
    }

    /**
     * The generation level of each version — the longest-path depth of its
     * "same instant" class in the contracted DAG — or {@code null} when the
     * rules are contradictory on this DAG (an equality class containing two
     * versions related by a directed path).
     */
    private static Map<Version, Integer> levelsOrNull(Collection<Version> versions) {
        Set<Version> all = new HashSet<>(versions);
        Map<Version, List<Version>> children = childrenOf(versions);

        // Rules 2-3: all children of a version share one generation instant.
        // Union the children sets into "same instant" classes.
        Map<Version, Version> unionFind = new HashMap<>();
        for (List<Version> siblings : children.values()) {
            for (int i = 1; i < siblings.size(); i++) {
                Version a = find(unionFind, siblings.get(0));
                Version b = find(unionFind, siblings.get(i));
                if (a != b) {
                    unionFind.put(a, b);
                }
            }
        }

        // Contract the DAG over the classes. A parent-child edge inside one
        // class would force an instant to strictly precede itself.
        Map<Version, Set<Version>> successors = new HashMap<>();
        Map<Version, Integer> incoming = new HashMap<>();
        for (Version v : all) {
            incoming.putIfAbsent(find(unionFind, v), 0);
        }
        for (Map.Entry<Version, List<Version>> e : children.entrySet()) {
            Version from = find(unionFind, e.getKey());
            for (Version child : e.getValue()) {
                Version to = find(unionFind, child);
                if (from == to) {
                    return null;
                }
                if (successors.computeIfAbsent(from, k -> new HashSet<>()).add(to)) {
                    incoming.merge(to, 1, Integer::sum);
                }
            }
        }

        // Longest-path levels over the contracted DAG (Kahn). A leftover
        // class means a cycle between classes: contradictory rules again.
        Map<Version, Integer> level = new HashMap<>();
        Deque<Version> ready = new ArrayDeque<>();
        incoming.forEach((cls, in) -> {
            if (in == 0) {
                level.put(cls, 0);
                ready.add(cls);
            }
        });
        int processed = 0;
        while (!ready.isEmpty()) {
            Version cls = ready.removeFirst();
            processed++;
            for (Version next : successors.getOrDefault(cls, Set.of())) {
                level.merge(next, level.get(cls) + 1, Integer::max);
                if (incoming.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (processed < incoming.size()) {
            return null;
        }

        Map<Version, Integer> result = new HashMap<>();
        for (Version v : all) {
            result.put(v, level.get(find(unionFind, v)));
        }
        return result;
    }

    /**
     * The children of each version, restricted to the given collection
     * (parents outside it are ignored, as in
     * {@link VersionGraphWriter#topologicalOrder}). Versions without
     * children — the final versions — have no entry.
     */
    private static Map<Version, List<Version>> childrenOf(Collection<Version> versions) {
        Set<Version> all = new HashSet<>(versions);
        Map<Version, List<Version>> children = new HashMap<>();
        for (Version v : all) {
            for (Version parent : v.getParents()) {
                if (all.contains(parent)) {
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(v);
                }
            }
        }
        return children;
    }

    private static Version find(Map<Version, Version> unionFind, Version v) {
        Version root = v;
        while (unionFind.containsKey(root)) {
            root = unionFind.get(root);
        }
        while (v != root) {
            v = unionFind.put(v, root);
        }
        return root;
    }
}
