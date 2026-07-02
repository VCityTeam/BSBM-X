package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.sparql.core.Quad;

/**
 * The <b>Inference validation</b> engine: checks the validity of versions —
 * and classifies the validity of <b>merges</b> — against a rule set
 * ({@link RuleLanguage#SHACL SHACL} shapes, an {@link RuleLanguage#RDFS RDFS}
 * or an {@link RuleLanguage#OWL OWL} ontology), implementing the
 * formalization of {@code Version-history-inference-validation.md}:
 * <ul>
 *   <li>the rules are evaluated on the triple projection π(S(v)) of the
 *       version's quad set — the union of its named graphs (§5.1);</li>
 *   <li>SHACL shapes are validated under the closed-world assumption with
 *       the Jena SHACL engine; RDFS/OWL ontologies are checked for logical
 *       consistency under the open-world assumption with the corresponding
 *       Jena reasoner (§5.2, §6);</li>
 *   <li>every merge node is classified by the outcome taxonomy of §8,
 *       crossing the parents' validity with the merge's validity:
 *       {@link MergeOutcome#PRESERVED}, {@link MergeOutcome#EMERGENT_VIOLATION}
 *       (manufactured by the merge policy itself),
 *       {@link MergeOutcome#REPAIRED} and
 *       {@link MergeOutcome#INHERITED_VIOLATION}.</li>
 * </ul>
 * Instances are immutable and reusable across versions and histories.
 */
public final class InferenceValidator {

    /** The validity verdict of a single version, with its violation reports. */
    public record VersionValidity(String versionId, boolean valid, List<String> violations) { }

    /**
     * The §8 outcome taxonomy of a merge node: the parents' validity crossed
     * with the merge's validity.
     */
    public enum MergeOutcome {
        /** All parents valid and the merge is valid. */
        PRESERVED,
        /** All parents valid but the merge is invalid: the violation was manufactured by the policy operator. */
        EMERGENT_VIOLATION,
        /** Some parent invalid but the merge is valid: the policy dropped the offending statements. */
        REPAIRED,
        /** Some parent invalid and the merge is invalid. */
        INHERITED_VIOLATION;

        static MergeOutcome of(boolean parentsAllValid, boolean mergeValid) {
            if (parentsAllValid) {
                return mergeValid ? PRESERVED : EMERGENT_VIOLATION;
            }
            return mergeValid ? REPAIRED : INHERITED_VIOLATION;
        }
    }

    /** The classified validity of one merge node. */
    public record MergeAssessment(String mergeId, List<String> parentIds, List<String> invalidParentIds,
                                  boolean mergeValid, MergeOutcome outcome) { }

    /**
     * The validation of a whole version history: the per-version verdicts
     * (in topological order) and the per-merge outcome classification.
     */
    public record HistoryReport(RuleLanguage language, List<VersionValidity> versions,
                                List<MergeAssessment> merges) {

        public boolean allValid() {
            return versions.stream().allMatch(VersionValidity::valid);
        }

        public long validCount() {
            return versions.stream().filter(VersionValidity::valid).count();
        }

        public long outcomeCount(MergeOutcome outcome) {
            return merges.stream().filter(m -> m.outcome() == outcome).count();
        }

        /** One-line summary: validity rate and merge outcome histogram (§9). */
        public String summary() {
            return validCount() + "/" + versions.size() + " versions valid; merges: "
                    + outcomeCount(MergeOutcome.PRESERVED) + " preserved, "
                    + outcomeCount(MergeOutcome.EMERGENT_VIOLATION) + " emergent-violation, "
                    + outcomeCount(MergeOutcome.REPAIRED) + " repaired, "
                    + outcomeCount(MergeOutcome.INHERITED_VIOLATION) + " inherited-violation";
        }
    }

    private final RuleLanguage language;
    /** Parsed shapes graph (SHACL regime only). */
    private final Shapes shapes;
    /** Schema-bound reasoner (RDFS/OWL regimes only). */
    private final Reasoner reasoner;

    private InferenceValidator(RuleLanguage language, Model rules) {
        this.language = language;
        switch (language) {
            case SHACL -> {
                this.shapes = Shapes.parse(rules.getGraph());
                this.reasoner = null;
            }
            case RDFS -> {
                this.shapes = null;
                this.reasoner = ReasonerRegistry.getRDFSReasoner().bindSchema(rules);
            }
            case OWL -> {
                this.shapes = null;
                this.reasoner = ReasonerRegistry.getOWLReasoner().bindSchema(rules);
            }
            default -> throw new IllegalArgumentException("Unsupported rule language: " + language);
        }
    }

    /** Creates a validator for the given rules, detecting their language. */
    public static InferenceValidator forRules(Model rules) {
        return forRules(rules, RuleLanguage.detect(rules));
    }

    /** Creates a validator for the given rules under the given language. */
    public static InferenceValidator forRules(Model rules, RuleLanguage language) {
        return new InferenceValidator(language, rules);
    }

    /** Loads a rules file (Turtle/RDF) and detects its language. */
    public static InferenceValidator fromFile(Path rulesFile) throws IOException {
        return forRules(loadRules(rulesFile));
    }

    /** Loads a rules file (Turtle/RDF) to be read under the given language. */
    public static InferenceValidator fromFile(Path rulesFile, RuleLanguage language) throws IOException {
        return forRules(loadRules(rulesFile), language);
    }

    private static Model loadRules(Path rulesFile) throws IOException {
        if (!Files.isRegularFile(rulesFile)) {
            throw new IOException("Missing rules file: " + rulesFile);
        }
        try {
            return RDFDataMgr.loadModel(rulesFile.toUri().toString());
        } catch (RiotException e) {
            throw new IOException("Invalid RDF in rules file " + rulesFile, e);
        }
    }

    public RuleLanguage getLanguage() {
        return language;
    }

    /**
     * Validates a single version: its quad set is projected to the union of
     * its named graphs (§5.1) and evaluated against the rules under the
     * regime of the rule language.
     */
    public VersionValidity validate(Version version) {
        Model data = ModelFactory.createDefaultModel();
        for (Quad quad : version.getData()) {
            data.getGraph().add(quad.asTriple());
        }
        return switch (language) {
            case SHACL -> validateShacl(version.getId(), data);
            case RDFS, OWL -> validateConsistency(version.getId(), data);
        };
    }

    /**
     * Validates a whole history: every version gets a verdict, and every
     * merge node (|pre(v)| >= 2) is classified by the §8 outcome taxonomy.
     * Parents that are not part of the given collection are ignored, like in
     * the topological ordering.
     */
    public HistoryReport validateHistory(Collection<Version> versions) {
        List<Version> ordered = VersionGraphWriter.topologicalOrder(versions);
        Map<String, VersionValidity> byId = new HashMap<>();
        List<VersionValidity> verdicts = new ArrayList<>(ordered.size());
        for (Version v : ordered) {
            VersionValidity validity = validate(v);
            byId.put(v.getId(), validity);
            verdicts.add(validity);
        }

        List<MergeAssessment> merges = new ArrayList<>();
        for (Version v : ordered) {
            if (v.getParents().size() < 2) {
                continue;
            }
            List<String> parentIds = v.getParents().stream().map(Version::getId).toList();
            List<String> invalidParents = parentIds.stream()
                    .filter(id -> byId.containsKey(id) && !byId.get(id).valid())
                    .toList();
            boolean mergeValid = byId.get(v.getId()).valid();
            merges.add(new MergeAssessment(v.getId(), parentIds, invalidParents, mergeValid,
                    MergeOutcome.of(invalidParents.isEmpty(), mergeValid)));
        }
        return new HistoryReport(language, List.copyOf(verdicts), List.copyOf(merges));
    }

    /** Closed-world constraint validation with the Jena SHACL engine. */
    private VersionValidity validateShacl(String versionId, Model data) {
        ValidationReport report = ShaclValidator.get().validate(shapes, data.getGraph());
        TreeSet<String> violations = new TreeSet<>();
        for (ReportEntry entry : report.getEntries()) {
            StringBuilder sb = new StringBuilder("focus ").append(entry.focusNode());
            if (entry.resultPath() != null) {
                sb.append(", path ").append(entry.resultPath());
            }
            sb.append(": ").append(entry.message());
            violations.add(sb.toString());
        }
        return new VersionValidity(versionId, report.conforms(), List.copyOf(violations));
    }

    /** Open-world consistency check with the schema-bound Jena reasoner. */
    private VersionValidity validateConsistency(String versionId, Model data) {
        InfModel inference = ModelFactory.createInfModel(reasoner, data);
        ValidityReport report = inference.validate();
        TreeSet<String> violations = new TreeSet<>();
        report.getReports().forEachRemaining(r -> {
            if (r.isError()) {
                violations.add("[" + r.getType() + "] " + r.getDescription());
            }
        });
        return new VersionValidity(versionId, report.isValid(), List.copyOf(violations));
    }
}
