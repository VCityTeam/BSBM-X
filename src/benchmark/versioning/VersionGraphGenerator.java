package benchmark.versioning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates a version graph from a handful of parameters, instead of reading
 * it from a file: number of versions, branches and merges, size of the
 * initial dataset and how much the dataset evolves between two versions.
 * <p>
 * Connection rules:
 * <ul>
 *   <li>the graph starts with a single root {@code V0} holding the initial
 *       dataset ({@code initialQuads} fresh quads);</li>
 *   <li>each of the {@code branches - 1} additional branches is opened by a
 *       transition forking from the head of a randomly chosen existing
 *       branch;</li>
 *   <li>the remaining transitions advance the head of a randomly chosen
 *       branch;</li>
 *   <li>the merges are evenly interleaved among those transitions; each
 *       merge combines the heads of 2 — or sometimes 3, when at least 3
 *       branches exist (octopus) — randomly chosen distinct branches, and
 *       becomes the new head of the first of them; the other merged
 *       branches keep their heads and stay active, as in git;</li>
 *   <li>every transition deletes {@code evolutionQuads / 2} random quads
 *       from its parent's dataset (capped by the dataset size) and adds
 *       {@code evolutionQuads - evolutionQuads / 2} fresh quads.</li>
 * </ul>
 * Transitions are named {@code V1..Vn} and merges {@code M1..Mm}, in
 * creation order.
 * <p>
 * The generation is deterministic for a given seed, and the DAG structure
 * does not depend on the merge policy: two independent random streams are
 * used, one for the structure (fork/branch/merge choices) and one for the
 * data (deletion picks). The same parameters replayed under different
 * policies therefore produce the same DAG — only the datasets downstream of
 * the merges differ.
 */
public final class VersionGraphGenerator {

    // Named graphs of the generated quads (BSBM-flavored). They are IRIs,
    // deliberately independent of the version identifiers.
    public static final String GRAPH_PRODUCTS = "http://example.org/graph/products";
    public static final String GRAPH_OFFERS = "http://example.org/graph/offers";
    public static final String GRAPH_REVIEWS = "http://example.org/graph/reviews";

    /**
     * The generation parameters.
     *
     * @param versions       total number of versions (root + transitions + merges)
     * @param branches       number of branches
     * @param merges         number of merge nodes
     * @param initialQuads   number of quads in the initial dataset of the root
     * @param evolutionQuads number of quads changed between two versions
     *                       (deletions + additions applied by each transition)
     * @param seed           random seed, for reproducible graphs
     */
    public record Parameters(int versions, int branches, int merges,
                             int initialQuads, int evolutionQuads, long seed) {
        public Parameters {
            if (versions < 1) {
                throw new IllegalArgumentException("versions must be >= 1 (got " + versions + ")");
            }
            if (branches < 1) {
                throw new IllegalArgumentException("branches must be >= 1 (got " + branches + ")");
            }
            if (merges < 0) {
                throw new IllegalArgumentException("merges must be >= 0 (got " + merges + ")");
            }
            if (initialQuads < 0) {
                throw new IllegalArgumentException("initial quads must be >= 0 (got " + initialQuads + ")");
            }
            if (evolutionQuads < 0) {
                throw new IllegalArgumentException("evolution quads must be >= 0 (got " + evolutionQuads + ")");
            }
            if (merges > 0 && branches < 2) {
                throw new IllegalArgumentException("merges require at least 2 branches");
            }
            // 1 root + (branches - 1) forks + merges must fit in the budget.
            if (versions < branches + merges) {
                throw new IllegalArgumentException("versions must be >= branches + merges ("
                        + versions + " < " + branches + " + " + merges + ")");
            }
        }
    }

    /** Draws for the DAG structure: fork parents, branch and merge choices. */
    private final Random structureRng;
    /** Draws for the data: which quads each transition deletes. */
    private final Random dataRng;
    private final Parameters params;
    private final VersionGraph graph;
    /** Head version of each branch; index = branch number. */
    private final List<Version> heads = new ArrayList<>();
    private long quadCounter = 0;
    private int nextVersionNo = 1;
    private int nextMergeNo = 1;

    private VersionGraphGenerator(Parameters params, MergePolicy policy) {
        this.params = params;
        this.graph = new VersionGraph(policy);
        this.structureRng = new Random(params.seed());
        this.dataRng = new Random(params.seed() + 1);
    }

    /**
     * Generates a version graph with the given parameters under the given
     * global merge policy.
     */
    public static VersionGraph generate(Parameters params, MergePolicy policy) {
        return new VersionGraphGenerator(params, policy).build();
    }

    private VersionGraph build() {
        Set<Quad> initialData = new HashSet<>();
        for (int i = 0; i < params.initialQuads(); i++) {
            initialData.add(freshQuad());
        }
        heads.add(graph.createRoot("V0", initialData));

        // Open the additional branches first, then interleave the merges
        // evenly among the remaining transitions.
        int forks = params.branches() - 1;
        for (int i = 0; i < forks; i++) {
            fork();
        }
        int remaining = params.versions() - 1 - forks;
        int mergesDone = 0;
        for (int i = 1; i <= remaining; i++) {
            if ((long) i * params.merges() / remaining > mergesDone) {
                merge();
                mergesDone++;
            } else {
                advance();
            }
        }
        return graph;
    }

    /** Opens a new branch: a transition forking from a random existing head. */
    private void fork() {
        Version parent = heads.get(structureRng.nextInt(heads.size()));
        heads.add(transitionFrom(parent));
    }

    /** Advances a random branch: a transition from its head. */
    private void advance() {
        int branch = structureRng.nextInt(heads.size());
        heads.set(branch, transitionFrom(heads.get(branch)));
    }

    private Version transitionFrom(Version parent) {
        int deletionCount = Math.min(params.evolutionQuads() / 2, parent.getData().size());
        int additionCount = params.evolutionQuads() - params.evolutionQuads() / 2;

        // Pick the deleted quads from a sorted copy so the choice only
        // depends on the data stream, not on set iteration order.
        List<Quad> candidates = new ArrayList<>(parent.getData());
        candidates.sort(Comparator.comparing(Quad::toString));
        Set<Quad> deletions = new HashSet<>();
        for (int i = 0; i < deletionCount; i++) {
            deletions.add(candidates.remove(dataRng.nextInt(candidates.size())));
        }
        Set<Quad> additions = new HashSet<>();
        for (int i = 0; i < additionCount; i++) {
            additions.add(freshQuad());
        }
        return graph.createTransition("V" + nextVersionNo++, parent, additions, deletions);
    }

    /**
     * Merges the heads of 2 (or 3, octopus) distinct random branches; the
     * merge becomes the new head of the first of them.
     */
    private void merge() {
        int parentCount = heads.size() >= 3 && structureRng.nextBoolean() ? 3 : 2;
        List<Integer> branches = new ArrayList<>();
        for (int b = 0; b < heads.size(); b++) {
            branches.add(b);
        }
        Collections.shuffle(branches, structureRng);
        List<Version> parents = branches.subList(0, parentCount).stream()
                .map(heads::get)
                .toList();
        Version merge = graph.createMerge("M" + nextMergeNo++, parents);
        heads.set(branches.get(0), merge);
    }

    /**
     * A fresh, never-seen-before quad. Quads rotate over the three named
     * graphs and are fully determined by an internal counter, so the
     * additions do not depend on any random stream.
     */
    private Quad freshQuad() {
        long n = quadCounter++;
        return switch ((int) (n % 3)) {
            case 0 -> new Quad("ex:product" + n, "rdf:type", "bsbm:Product", GRAPH_PRODUCTS);
            case 1 -> new Quad("ex:offer" + n, "bsbm:price", "\"" + (10 + n * 7 % 90) + ".0\"", GRAPH_OFFERS);
            default -> new Quad("ex:review" + n, "bsbm:reviewFor", "ex:product" + (n - 2), GRAPH_REVIEWS);
        };
    }
}
