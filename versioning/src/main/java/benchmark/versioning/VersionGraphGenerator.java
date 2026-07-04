package benchmark.versioning;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;

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
 *       branches keep their heads and stay active, as in git. Head
 *       combinations whose merge would leave the DAG without a legal
 *       PROV-O generation-time assignment (see {@link VersionTimestamps})
 *       are skipped — the first schedulable combination in the shuffled
 *       order is used, and if none exists the merge slot falls back to a
 *       transition and the merge is retried later;</li>
 *   <li>every transition deletes {@code evolutionQuads / 2} random quads
 *       from its parent's dataset (capped by the dataset size) and adds
 *       {@code evolutionQuads - evolutionQuads / 2} fresh quads.</li>
 * </ul>
 * Transitions are named {@code V1..Vn} and merges {@code M1..Mm}, in
 * creation order. Fresh quads are BSBM-flavored Apache Jena {@link Quad}s
 * (see {@link Vocabulary}) following a 14-shape catalog cycling over the
 * four content graphs: each block describes a product (label, type,
 * feature, producer), a vendor (type, country), an offer on the product
 * (price, product and vendor links, delivery days, type) and a review of
 * it (reference, rating, reviewer) — see {@link #freshQuad()}.
 * <p>
 * Once the DAG is built, every version receives its PROV-O
 * {@code prov:generatedAtTime} and {@code prov:invalidatedAtTime}
 * ({@link VersionTimestamps}): the instants are anchored at the wall-clock
 * instant the generation ran and advance by one second per generation level,
 * so that every version is generated strictly after its parents, all
 * versions following a fork are generated at the same instant (which is the
 * fork's invalidation instant) and the final versions stay valid.
 * <p>
 * Merges apply either one global policy ({@link #generate}) or a policy
 * drawn at random per merge ({@link #generateRandomPolicies}); each merge
 * records its policy ({@link Version#getMergePolicy()}), which the PROV-O
 * export preserves.
 * <p>
 * The generation is deterministic for a given seed (only the wall-clock
 * anchor of the timestamps changes between runs), and the DAG structure
 * does not depend on the merge policies: three independent random streams
 * are used — one for the structure (fork/branch/merge choices), one for the
 * data (deletion picks) and one for the per-merge policy draws of
 * {@link #generateRandomPolicies} — and the schedulability check is purely
 * structural. The same parameters replayed under a different fixed policy,
 * or under random per-merge policies, therefore produce the same DAG — only
 * the datasets downstream of the merges differ.
 */
public final class VersionGraphGenerator {

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
    /** Draws for the per-merge policies of {@link #generateRandomPolicies}. */
    private final Random policyRng;
    private final Parameters params;
    /** Policy applied to every merge; {@code null} = random per merge. */
    private final MergePolicy fixedPolicy;
    private final VersionGraph graph;
    /** Head version of each branch; index = branch number. */
    private final List<Version> heads = new ArrayList<>();
    private long quadCounter = 0;
    private int nextVersionNo = 1;
    private int nextMergeNo = 1;

    private VersionGraphGenerator(Parameters params, MergePolicy fixedPolicy) {
        this.params = params;
        this.fixedPolicy = fixedPolicy;
        this.graph = new VersionGraph(fixedPolicy);
        this.structureRng = new Random(params.seed());
        this.dataRng = new Random(params.seed() + 1);
        this.policyRng = new Random(params.seed() + 2);
    }

    /**
     * Generates a version graph with the given parameters under the given
     * global merge policy, applied to every merge.
     */
    public static VersionGraph generate(Parameters params, MergePolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null:"
                    + " use generateRandomPolicies for random per-merge policies");
        }
        return new VersionGraphGenerator(params, policy).build();
    }

    /**
     * Generates a version graph with the given parameters where <b>each
     * merge draws its own policy at random</b>, uniformly over
     * {@link MergePolicy}, from a dedicated random stream — deterministic
     * for a given seed. The DAG structure and the transition data are
     * identical to the fixed-policy graphs of the same seed; only the
     * datasets downstream of the merges differ. Each merge records its
     * policy ({@link Version#getMergePolicy()}), which the PROV-O export
     * preserves, so the reloaded history stays verifiable merge by merge.
     */
    public static VersionGraph generateRandomPolicies(Parameters params) {
        return new VersionGraphGenerator(params, null).build();
    }

    private VersionGraph build() {
        // Rule 1: the timestamps are anchored at the wall-clock instant the
        // graph was programmatically generated (whole seconds, so the
        // xsd:dateTime literals round-trip losslessly).
        Instant generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        Set<Quad> initialData = new HashSet<>();
        for (int i = 0; i < params.initialQuads(); i++) {
            initialData.add(freshQuad());
        }
        heads.add(graph.createRoot("V0", initialData));

        // Open the additional branches first, then interleave the merges
        // evenly among the remaining transitions. A merge slot whose head
        // combinations are all unschedulable falls back to a transition and
        // the merge is retried on the next slot.
        int forks = params.branches() - 1;
        for (int i = 0; i < forks; i++) {
            fork();
        }
        int remaining = params.versions() - 1 - forks;
        int mergesDone = 0;
        for (int i = 1; i <= remaining; i++) {
            if ((long) i * params.merges() / remaining > mergesDone && tryMerge()) {
                mergesDone++;
            } else {
                advance();
            }
        }
        if (mergesDone < params.merges()) {
            throw new IllegalStateException("Only " + mergesDone + " of " + params.merges()
                    + " merges admit a PROV-O generation-time assignment with these parameters;"
                    + " try another seed or a larger version budget");
        }

        VersionTimestamps.assign(graph.getVersions(), generatedAt, Duration.ofSeconds(1));
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
     * <p>
     * Not every head combination is legal: the merge must keep the DAG
     * schedulable, i.e. still admit a PROV-O generation-time assignment
     * where all versions following a fork are generated at the same instant
     * (e.g. merging a head with one of its own children never is — the
     * merge would have to be generated both at the same instant as, and
     * strictly after, that child). The first schedulable combination in the
     * shuffled order is used, preferring the drawn parent count; when no
     * combination is schedulable nothing is created and the caller falls
     * back to a transition.
     *
     * @return whether a merge node was created
     */
    private boolean tryMerge() {
        int parentCount = heads.size() >= 3 && structureRng.nextBoolean() ? 3 : 2;
        List<Integer> branches = new ArrayList<>();
        for (int b = 0; b < heads.size(); b++) {
            branches.add(b);
        }
        Collections.shuffle(branches, structureRng);
        List<Integer> sizes = parentCount == 3 ? List.of(3, 2) : List.of(2);
        for (int size : sizes) {
            for (int[] combination : combinations(branches.size(), size)) {
                List<Version> parents = new ArrayList<>();
                for (int position : combination) {
                    parents.add(heads.get(branches.get(position)));
                }
                if (VersionTimestamps.canAddMerge(graph.getVersions(), parents)) {
                    Version merge = graph.createMerge("M" + nextMergeNo++, parents, nextPolicy());
                    heads.set(branches.get(combination[0]), merge);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The policy of the next merge: the fixed global policy, or a uniform
     * draw from the dedicated policy stream in random mode — one draw per
     * merge created, so the draws stay aligned with the merge numbering
     * whatever combinations the schedulability check rejected.
     */
    private MergePolicy nextPolicy() {
        if (fixedPolicy != null) {
            return fixedPolicy;
        }
        MergePolicy[] policies = MergePolicy.values();
        return policies[policyRng.nextInt(policies.length)];
    }

    /**
     * All the size-{@code k} combinations of {@code 0..n-1}, in
     * lexicographic order — so the first combinations are the first
     * positions of the shuffled branch list, matching the plain random
     * choice when it is schedulable.
     */
    private static List<int[]> combinations(int n, int k) {
        List<int[]> all = new ArrayList<>();
        int[] combination = new int[k];
        for (int i = 0; i < k; i++) {
            combination[i] = i;
        }
        while (true) {
            all.add(combination.clone());
            int i = k - 1;
            while (i >= 0 && combination[i] == n - k + i) {
                i--;
            }
            if (i < 0) {
                return all;
            }
            combination[i]++;
            for (int j = i + 1; j < k; j++) {
                combination[j] = combination[j - 1] + 1;
            }
        }
    }

    /** Country codes of the generated vendors, rotated per block. */
    private static final String[] COUNTRIES = {"US", "DE", "FR", "GB", "JP"};

    /**
     * A fresh, never-seen-before quad, built with Apache Jena. The quads
     * follow a BSBM-flavored catalog of 14 shapes cycling over the four
     * content graphs: block {@code b = counter / 14} describes one product
     * ({@code ex:product<b>}: language-tagged label, type, feature,
     * producer), the offer's vendor ({@code ex:vendor<b>}: type, country),
     * one offer on the product ({@code ex:offer<b>}: price, product and
     * vendor links, delivery days, type) and one review of it
     * ({@code ex:review<b>}: reference, rating, reviewer). Features,
     * producers and reviewers are drawn from small shared pools, so blocks
     * reference common entities; literals mix plain strings, language tags
     * and typed {@code xsd:integer}s.
     * <p>
     * Within a block, every quad required by a SHACL witness constraint of
     * {@code rules/shacl-shapes.ttl} is emitted <b>before</b> the quad that
     * targets it (the label before the product type, the price and the
     * references before the offer type, the vendor type before the vendor
     * link), so a version built of additions alone is closed-world valid at
     * any counter frontier: SHACL violations only appear where a deletion
     * or a merge actually damaged the data.
     * <p>
     * Every quad is fully determined by the internal counter, so the
     * additions do not depend on any random stream, and no two counter
     * values ever produce the same quad.
     */
    private Quad freshQuad() {
        long n = quadCounter++;
        long block = n / 14;
        Node products = Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS);
        Node offers = Vocabulary.iri(Vocabulary.GRAPH_OFFERS);
        Node reviews = Vocabulary.iri(Vocabulary.GRAPH_REVIEWS);
        Node vendors = Vocabulary.iri(Vocabulary.GRAPH_VENDORS);
        Node product = Vocabulary.ex("product" + block);
        Node offer = Vocabulary.ex("offer" + block);
        Node review = Vocabulary.ex("review" + block);
        Node vendor = Vocabulary.ex("vendor" + block);
        return switch ((int) (n % 14)) {
            // The block's product: label, type, feature and producer.
            case 0 -> Vocabulary.quad(products, product,
                    Vocabulary.label(), Vocabulary.langLiteral("Product " + block, "en"));
            case 1 -> Vocabulary.quad(products, product,
                    Vocabulary.rdfType(), Vocabulary.bsbm("Product"));
            case 2 -> Vocabulary.quad(products, product,
                    Vocabulary.bsbm("productFeature"), Vocabulary.ex("feature" + block % 7));
            case 3 -> Vocabulary.quad(products, product,
                    Vocabulary.bsbm("producer"), Vocabulary.ex("producer" + block % 5));
            // The vendor of the block's offer: type and country.
            case 4 -> Vocabulary.quad(vendors, vendor,
                    Vocabulary.rdfType(), Vocabulary.bsbm("Vendor"));
            case 5 -> Vocabulary.quad(vendors, vendor,
                    Vocabulary.bsbm("country"),
                    Vocabulary.literal(COUNTRIES[(int) (block % COUNTRIES.length)]));
            // The offer on the block's product: price, references, delivery
            // days, type.
            case 6 -> Vocabulary.quad(offers, offer,
                    Vocabulary.bsbm("price"), Vocabulary.literal((10 + n * 7 % 90) + "." + n % 10));
            case 7 -> Vocabulary.quad(offers, offer, Vocabulary.bsbm("product"), product);
            case 8 -> Vocabulary.quad(offers, offer, Vocabulary.bsbm("vendor"), vendor);
            case 9 -> Vocabulary.quad(offers, offer,
                    Vocabulary.bsbm("deliveryDays"), Vocabulary.integerLiteral(1 + n % 7));
            case 10 -> Vocabulary.quad(offers, offer,
                    Vocabulary.rdfType(), Vocabulary.bsbm("Offer"));
            // The review of the block's product: reference, rating, reviewer.
            case 11 -> Vocabulary.quad(reviews, review, Vocabulary.bsbm("reviewFor"), product);
            case 12 -> Vocabulary.quad(reviews, review,
                    Vocabulary.bsbm("rating1"), Vocabulary.integerLiteral(1 + n % 10));
            default -> Vocabulary.quad(reviews, review,
                    Vocabulary.bsbm("reviewer"), Vocabulary.ex("person" + block % 6));
        };
    }
}
