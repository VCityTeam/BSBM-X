package benchmark.versioning;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BinaryOperator;

import org.apache.jena.sparql.core.Quad;

public enum MergePolicy {
    UNION(MergePolicy::union),
    INTERSECTION(MergePolicy::intersection),
    SYMMETRIC_DIFFERENCE(MergePolicy::symmetricDifference);

    private final BinaryOperator<Set<Quad>> operator;

    MergePolicy(BinaryOperator<Set<Quad>> operator) {
        this.operator = operator;
    }

    /**
     * Applies the global policy operator to the RDF datasets of all parents.
     * The parents' datasets are given as a list (not a set) so that parents
     * with identical states are not collapsed, which matters for the
     * symmetric difference operator.
     * The operators are commutative and associative, so the reduction order
     * does not matter and n-ary ("octopus") merges are supported.
     */
    public Set<Quad> apply(List<Set<Quad>> parentsData) {
        if (parentsData == null || parentsData.isEmpty()) {
            return new HashSet<>();
        }
        return parentsData.stream().reduce(this.operator).orElse(new HashSet<>());
    }

    private static Set<Quad> union(Set<Quad> s1, Set<Quad> s2) {
        Set<Quad> result = new HashSet<>(s1);
        result.addAll(s2);
        return result;
    }

    private static Set<Quad> intersection(Set<Quad> s1, Set<Quad> s2) {
        Set<Quad> result = new HashSet<>(s1);
        result.retainAll(s2);
        return result;
    }

    private static Set<Quad> symmetricDifference(Set<Quad> s1, Set<Quad> s2) {
        Set<Quad> result = new HashSet<>(s1);
        result.addAll(s2);
        Set<Quad> common = new HashSet<>(s1);
        common.retainAll(s2);
        result.removeAll(common);
        return result;
    }
}
