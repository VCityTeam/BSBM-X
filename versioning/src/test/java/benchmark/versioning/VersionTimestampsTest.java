package benchmark.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The PROV-O lifecycle instants of a version graph
 * ({@link VersionTimestamps}), following four rules:
 * <ol>
 *   <li>a version is generated at the (wall-clock anchored) time it was
 *       programmatically generated;</li>
 *   <li>a version is invalidated at the instant its following versions are
 *       generated;</li>
 *   <li>all versions following a fork are generated at the same instant;</li>
 *   <li>final versions are still valid — no invalidation instant.</li>
 * </ol>
 */
class VersionTimestampsTest {

    private static final Instant BASE = Instant.parse("2026-07-03T12:00:00Z");
    private static final Duration STEP = Duration.ofSeconds(1);

    /**
     * Every graph the generator produces obeys the four rules, whatever the
     * seed — including seeds where the plain random merge choice would have
     * been unschedulable and another head combination had to be picked.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 7, 42, 1234, 987654})
    void generatedGraphsHaveConsistentTimestamps(long seed) {
        VersionGraphGenerator.Parameters parameters =
                new VersionGraphGenerator.Parameters(24, 4, 5, 20, 6, seed);
        VersionGraph graph = VersionGraphGenerator.generate(parameters, MergePolicy.UNION);

        Collection<Version> versions = graph.getVersions();
        assertEquals(5, versions.stream().filter(v -> v.getParents().size() >= 2).count(),
                "the generator must honor the merge budget");
        for (Version v : versions) {
            assertNotNull(v.getGeneratedAtTime(),
                    () -> v.getId() + " must carry a prov:generatedAtTime");
        }
        assertTrue(VersionConsistencyChecker.hasConsistentTimestamps(versions),
                "the generated timestamps must obey the generation/invalidation rules");

        Map<Version, List<Version>> followers = followersOf(versions);
        for (Version v : versions) {
            List<Version> following = followers.get(v);
            if (following == null) {
                // Rule 4: final versions are still valid.
                assertNull(v.getInvalidatedAtTime(),
                        () -> "final version " + v.getId() + " must have no prov:invalidatedAtTime");
                continue;
            }
            // Rule 3: all followers share one generation instant...
            Instant followersGeneration = following.get(0).getGeneratedAtTime();
            for (Version follower : following) {
                assertEquals(followersGeneration, follower.getGeneratedAtTime(),
                        () -> "versions following " + v.getId() + " must be generated at the same instant");
                assertTrue(follower.getGeneratedAtTime().isAfter(v.getGeneratedAtTime()),
                        () -> follower.getId() + " must be generated strictly after its parent " + v.getId());
            }
            // ... which is, rule 2, the invalidation instant of the version.
            assertEquals(followersGeneration, v.getInvalidatedAtTime(),
                    () -> v.getId() + " must be invalidated when its followers are generated");
        }
    }

    /**
     * A hand-built diamond: V0 forks into VA and VB, merged back by M. VA
     * and VB follow the fork V0, so they share one generation instant, which
     * invalidates V0; the final version M is still valid.
     */
    @Test
    void diamondFollowsTheFourRules() {
        Version v0 = new Version("V0", Set.of(), List.of());
        Version vA = new Version("VA", Set.of(), List.of(v0));
        Version vB = new Version("VB", Set.of(), List.of(v0));
        Version m = new Version("M", Set.of(), List.of(vA, vB));
        List<Version> versions = List.of(v0, vA, vB, m);

        VersionTimestamps.assign(versions, BASE, STEP);

        assertEquals(BASE, v0.getGeneratedAtTime());
        assertEquals(BASE.plusSeconds(1), vA.getGeneratedAtTime());
        assertEquals(vA.getGeneratedAtTime(), vB.getGeneratedAtTime(),
                "the versions following the fork V0 must be generated at the same instant");
        assertEquals(vA.getGeneratedAtTime(), v0.getInvalidatedAtTime(),
                "V0 must be invalidated when its followers are generated");
        assertEquals(BASE.plusSeconds(2), m.getGeneratedAtTime());
        assertEquals(m.getGeneratedAtTime(), vA.getInvalidatedAtTime());
        assertEquals(m.getGeneratedAtTime(), vB.getInvalidatedAtTime());
        assertNull(m.getInvalidatedAtTime(), "the final version M must still be valid");
        assertTrue(VersionConsistencyChecker.hasConsistentTimestamps(versions));
    }

    /**
     * Merging a version with one of its own children is unschedulable: the
     * merge and that child both follow the forked version, so they would
     * have to be generated at the same instant — yet the merge derives from
     * the child, so strictly after it.
     */
    @Test
    void mergingAVersionWithItsOwnChildIsUnschedulable() {
        Version f = new Version("F", Set.of(), List.of());
        Version b = new Version("B", Set.of(), List.of(f));
        Version m = new Version("M", Set.of(), List.of(f, b));
        List<Version> versions = List.of(f, b, m);

        assertFalse(VersionTimestamps.canAddMerge(List.of(f, b), List.of(f, b)),
                "the generator must reject a merge of a version with its own child");
        assertThrows(IllegalStateException.class,
                () -> VersionTimestamps.assign(versions, BASE, STEP));
    }

    /**
     * Timestamp violations are detected by the consistency checker: a
     * still-valid non-final version, an invalidated final version and fork
     * followers generated at different instants are all inconsistent.
     */
    @Test
    void checkerDetectsTimestampViolations() {
        Version v0 = new Version("V0", Set.of(), List.of(),
                BASE, BASE.plusSeconds(1));
        Version vA = new Version("VA", Set.of(), List.of(v0),
                BASE.plusSeconds(1), null);
        Version vB = new Version("VB", Set.of(), List.of(v0),
                BASE.plusSeconds(1), null);
        assertTrue(VersionConsistencyChecker.hasConsistentTimestamps(List.of(v0, vA, vB)));

        // Fork followers generated at different instants (rule 3).
        Version vBLate = new Version("VB", Set.of(), List.of(v0),
                BASE.plusSeconds(2), null);
        assertFalse(VersionConsistencyChecker.hasConsistentTimestamps(List.of(v0, vA, vBLate)));

        // A version not invalidated when its follower is generated (rule 2).
        Version v0Late = new Version("V0", Set.of(), List.of(),
                BASE, BASE.plusSeconds(5));
        Version vC = new Version("VC", Set.of(), List.of(v0Late),
                BASE.plusSeconds(1), null);
        assertFalse(VersionConsistencyChecker.hasConsistentTimestamps(List.of(v0Late, vC)));

        // An invalidated final version (rule 4).
        Version invalidatedLeaf = new Version("V1", Set.of(), List.of(),
                BASE, BASE.plusSeconds(1));
        assertFalse(VersionConsistencyChecker.hasConsistentTimestamps(List.of(invalidatedLeaf)));

        // A follower not generated strictly after its parent.
        Version sameInstant = new Version("VD", Set.of(), List.of(v0),
                v0.getGeneratedAtTime(), null);
        assertFalse(VersionConsistencyChecker.hasConsistentTimestamps(List.of(v0, sameInstant)));
    }

    private static Map<Version, List<Version>> followersOf(Collection<Version> versions) {
        Map<Version, List<Version>> followers = new HashMap<>();
        for (Version v : versions) {
            for (Version parent : v.getParents()) {
                followers.computeIfAbsent(parent, k -> new ArrayList<>()).add(v);
            }
        }
        return followers;
    }
}
