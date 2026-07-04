package benchmark.versioning;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.Lang;
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
 *       {@link MergeOutcome#INHERITED_VIOLATION};</li>
 *   <li>under the RDFS/OWL entailment regimes, the <b>inferred knowledge</b>
 *       of each version — what the reasoner entails from the version's data
 *       and the ontology beyond what is asserted — can be materialized
 *       ({@link #inferredKnowledge}) and exported next to the version's
 *       N-Quads file as {@code <id>-<rdfs|owl>-infered.nq}
 *       ({@link #writeInferredFiles});</li>
 *   <li>independently of the regime, a <b>metagraph</b> rule set (native
 *       Apache Jena rule syntax, e.g. {@code rules/metagraph.rules}) can be
 *       run over the PROV-O description of the history enriched with the
 *       per-version verdicts as {@code mg:valid} facts:
 *       {@link #inferMetagraph} re-derives the merge outcomes at the
 *       metagraph level and cross-checks them against the engine's
 *       classification, and {@link #writeMetagraphFile} exports the derived
 *       statements as {@code metagraph-<shacl|rdfs|owl>-infered.ttl}
 *       (README §8).</li>
 * </ul>
 * Instances are immutable and reusable across versions and histories.
 */
public final class InferenceValidator {

    /**
     * Namespace of the derived metagraph vocabulary ({@code mg:}) of the
     * metagraph rule set (README §8): the {@code mg:valid} and
     * {@code mg:hasInvalidParent} facts asserted by {@link #inferMetagraph}
     * and every term the rules derive ({@code mg:outcome}, …).
     */
    public static final String META_NS = "http://example.org/versioning/meta#";

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

    /**
     * The metagraph verdict of one merge node (README §8): the engine's
     * outcome (§8 taxonomy, {@link MergeOutcome}) crossed with the
     * {@code mg:outcome} individual(s) the metagraph rules derived for the
     * merge entity.
     */
    public record MetagraphAssessment(String mergeId, MergeOutcome engineOutcome,
                                      Set<MergeOutcome> ruleOutcomes) {

        /** Whether the rules derived exactly the engine's outcome. */
        public boolean agrees() {
            return ruleOutcomes.equals(Set.of(engineOutcome));
        }
    }

    /**
     * The result of running a metagraph rule set over a history (README §8):
     * the full inference model over the PROV-O description (asserted plus
     * derived statements), the derived statements alone, and the per-merge
     * agreement between the rule-derived {@code mg:outcome} and the engine's
     * outcome taxonomy.
     */
    public record MetagraphReport(InfModel inference, Model derived,
                                  List<MetagraphAssessment> merges) {

        /** Whether the rules re-derived the engine's outcome for every merge. */
        public boolean allAgree() {
            return merges.stream().allMatch(MetagraphAssessment::agrees);
        }
    }

    private final RuleLanguage language;
    /** Parsed shapes graph (SHACL regime only). */
    private final Shapes shapes;
    /** Schema-bound reasoner (RDFS/OWL regimes only). */
    private final Reasoner reasoner;
    /**
     * Deductive closure of the <b>empty</b> dataset under the schema-bound
     * reasoner (RDFS/OWL regimes only): the statements entailed by the
     * ontology alone, including the axiomatic vocabulary. Subtracting it
     * from a version's closure leaves the version-specific inferences.
     */
    private final Model schemaClosure;

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
        this.schemaClosure = reasoner == null ? null : materialize(
                ModelFactory.createInfModel(reasoner, ModelFactory.createDefaultModel()));
    }

    private static Model materialize(InfModel inference) {
        Model closure = ModelFactory.createDefaultModel();
        inference.listStatements().forEachRemaining(closure::add);
        return closure;
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
        Model data = tripleProjection(version);
        return switch (language) {
            case SHACL -> validateShacl(version.getId(), data);
            case RDFS, OWL -> validateConsistency(version.getId(), data);
        };
    }

    /** The triple projection π(S(v)) of a version: the union of its named graphs (§5.1). */
    private static Model tripleProjection(Version version) {
        Model data = ModelFactory.createDefaultModel();
        for (Quad quad : version.getData()) {
            data.getGraph().add(quad.asTriple());
        }
        return data;
    }

    /**
     * Whether the rule language <b>entails</b> new statements: {@code true}
     * for the RDFS/OWL entailment regimes, {@code false} for the SHACL
     * constraint regime (which validates but infers nothing).
     */
    public boolean supportsInference() {
        return language.getAssumption() == RuleLanguage.WorldAssumption.OPEN;
    }

    /**
     * Materializes the <b>inferred knowledge</b> of a version: every
     * statement entailed by the version's triple projection together with
     * the ontology (the deductive closure of the schema-bound reasoner) that
     * is neither asserted in the version nor already entailed by the
     * ontology alone. Under RDFS this is what domains, ranges and class
     * hierarchies add; under OWL also what the negative and equality axioms
     * add (e.g. {@code owl:sameAs} from a functional property).
     *
     * @return the inferred statements, as quads in the
     *         {@link Vocabulary#GRAPH_INFERRED} named graph
     * @throws IllegalStateException under the SHACL constraint regime
     */
    public Set<Quad> inferredKnowledge(Version version) {
        requireEntailmentRegime();
        Model data = tripleProjection(version);
        InfModel inference = ModelFactory.createInfModel(reasoner, data);
        Node graph = Vocabulary.iri(Vocabulary.GRAPH_INFERRED);
        Set<Quad> inferred = new HashSet<>();
        inference.listStatements().forEachRemaining(statement -> {
            if (!data.contains(statement) && !schemaClosure.contains(statement)) {
                inferred.add(new Quad(graph, statement.asTriple()));
            }
        });
        return inferred;
    }

    /**
     * Name of the inferred-knowledge file of the version with the given id:
     * {@code <sanitized-id>-<rdfs|owl>-infered.nq}, located next to the
     * version's own {@code <sanitized-id>.nq} file.
     */
    public String inferredFileNameOf(String versionId) {
        return VersionGraphWriter.sanitize(versionId) + "-"
                + language.name().toLowerCase(Locale.ROOT) + "-infered.nq";
    }

    /**
     * Writes the inferred knowledge of <b>each version in a different
     * file</b> inside the given directory: for every version {@code <id>},
     * one N-Quads file {@code <id>-<rdfs|owl>-infered.nq} holding all the
     * statements of {@link #inferredKnowledge} (in the
     * {@link Vocabulary#GRAPH_INFERRED} named graph), preceded by a
     * {@code #} comment header. Versions are written in topological order
     * and the lines are sorted, so the export is deterministic.
     *
     * @return the list of files written, in topological order
     * @throws IllegalStateException under the SHACL constraint regime
     */
    public List<Path> writeInferredFiles(Collection<Version> versions, Path directory) throws IOException {
        requireEntailmentRegime();
        Files.createDirectories(directory);
        List<Path> written = new ArrayList<>();
        for (Version v : VersionGraphWriter.topologicalOrder(versions)) {
            Set<Quad> inferred = inferredKnowledge(v);
            StringBuilder sb = new StringBuilder();
            sb.append("# ===== Inferred knowledge export (N-Quads) =====\n");
            sb.append("# version: ").append(v.getId()).append('\n');
            sb.append("# rule language: ").append(language)
                    .append(" (").append(language.getAssumption()).append(" regime)\n");
            sb.append("# asserted quads: ").append(v.getData().size()).append('\n');
            sb.append("# inferred statements: ").append(inferred.size())
                    .append(" (named graph ").append(Vocabulary.GRAPH_INFERRED).append(")\n");
            for (String line : VersionGraphWriter.nquadLines(inferred)) {
                sb.append(line).append('\n');
            }
            Path file = directory.resolve(inferredFileNameOf(v.getId()));
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            written.add(file);
        }
        return written;
    }

    private void requireEntailmentRegime() {
        if (!supportsInference()) {
            throw new IllegalStateException("The " + language + " constraint regime validates but"
                    + " entails nothing: inferred knowledge only exists under the RDFS/OWL"
                    + " entailment regimes");
        }
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

    /**
     * Parses a <b>metagraph</b> rule set: a file in the native Apache Jena
     * rule syntax (e.g. {@code rules/metagraph.rules}) — not RDF, so it is
     * neither a {@link RuleLanguage} nor loadable with {@link #fromFile}.
     * These rules reason over the PROV-O description of the version graph
     * (README §8), unlike the SHACL/RDFS/OWL rule sets, which evaluate the
     * versions' content.
     */
    public static List<Rule> loadMetagraphRules(Path rulesFile) throws IOException {
        if (!Files.isRegularFile(rulesFile)) {
            throw new IOException("Missing metagraph rules file: " + rulesFile);
        }
        try (BufferedReader reader = Files.newBufferedReader(rulesFile, StandardCharsets.UTF_8)) {
            return Rule.parseRules(Rule.rulesParserFromReader(reader));
        } catch (Rule.ParserException e) {
            throw new IOException("Invalid Jena rules in metagraph rules file " + rulesFile, e);
        }
    }

    /**
     * Runs a <b>metagraph</b> rule set (README §8) over the PROV-O
     * description of the given versions: validates the history with this
     * validator's rules, then delegates to
     * {@link #inferMetagraph(Collection, MergePolicy, List, HistoryReport)}.
     */
    public MetagraphReport inferMetagraph(Collection<Version> versions, MergePolicy policy,
                                          List<Rule> metagraphRules) {
        return inferMetagraph(versions, policy, metagraphRules, validateHistory(versions));
    }

    /**
     * Runs a <b>metagraph</b> rule set (README §8) over the PROV-O
     * description of the given versions, reusing already-computed verdicts:
     * <ol>
     *   <li>builds the PROV-O model of the history ({@link ProvOWriter});</li>
     *   <li>asserts every verdict as an {@code mg:valid} fact on its version
     *       entity, and every invalid parent of a merge as an
     *       {@code mg:hasInvalidParent} fact — the latter keeps the rules'
     *       closed-world outcome rules stable under forward chaining, since
     *       the facts are in the base data before any rule fires;</li>
     *   <li>computes the deductive closure of the metagraph rules with a
     *       forward {@link GenericRuleReasoner}, and collects the derived
     *       statements and the rule-derived outcome of every merge.</li>
     * </ol>
     */
    public MetagraphReport inferMetagraph(Collection<Version> versions, MergePolicy policy,
                                          List<Rule> metagraphRules, HistoryReport verdicts) {
        Model provenance = ProvOWriter.model(versions, policy);
        Property valid = provenance.createProperty(META_NS + "valid");
        for (VersionValidity verdict : verdicts.versions()) {
            provenance.getResource(ProvOWriter.entityIriOf(verdict.versionId()))
                    .addProperty(valid, provenance.createTypedLiteral(verdict.valid()));
        }
        Property hasInvalidParent = provenance.createProperty(META_NS + "hasInvalidParent");
        for (MergeAssessment merge : verdicts.merges()) {
            for (String parentId : merge.invalidParentIds()) {
                provenance.getResource(ProvOWriter.entityIriOf(merge.mergeId()))
                        .addProperty(hasInvalidParent,
                                provenance.getResource(ProvOWriter.entityIriOf(parentId)));
            }
        }

        GenericRuleReasoner metagraphReasoner = new GenericRuleReasoner(metagraphRules);
        metagraphReasoner.setMode(GenericRuleReasoner.FORWARD);
        InfModel inference = ModelFactory.createInfModel(metagraphReasoner, provenance);

        Model derived = ModelFactory.createDefaultModel();
        derived.setNsPrefixes(provenance);
        derived.setNsPrefix("mg", META_NS);
        inference.listStatements().forEachRemaining(statement -> {
            if (!provenance.contains(statement)) {
                derived.add(statement);
            }
        });

        Property outcome = inference.createProperty(META_NS + "outcome");
        List<MetagraphAssessment> merges = new ArrayList<>();
        for (MergeAssessment merge : verdicts.merges()) {
            Set<MergeOutcome> ruleOutcomes = EnumSet.noneOf(MergeOutcome.class);
            StmtIterator it = inference.getResource(ProvOWriter.entityIriOf(merge.mergeId()))
                    .listProperties(outcome);
            while (it.hasNext()) {
                MergeOutcome mapped = outcomeOf(it.next().getObject());
                if (mapped != null) {
                    ruleOutcomes.add(mapped);
                }
            }
            merges.add(new MetagraphAssessment(merge.mergeId(), merge.outcome(), ruleOutcomes));
        }
        return new MetagraphReport(inference, derived, List.copyOf(merges));
    }

    /**
     * Maps an {@code mg:outcome} individual derived by the metagraph rules
     * ({@code mg:Preserved}, {@code mg:EmergentViolation}, {@code mg:Repaired},
     * {@code mg:InheritedViolation}) to the §8 taxonomy, or {@code null} for
     * any other node.
     */
    private static MergeOutcome outcomeOf(RDFNode node) {
        if (!node.isURIResource() || !node.asResource().getURI().startsWith(META_NS)) {
            return null;
        }
        return switch (node.asResource().getLocalName()) {
            case "Preserved" -> MergeOutcome.PRESERVED;
            case "EmergentViolation" -> MergeOutcome.EMERGENT_VIOLATION;
            case "Repaired" -> MergeOutcome.REPAIRED;
            case "InheritedViolation" -> MergeOutcome.INHERITED_VIOLATION;
            default -> null;
        };
    }

    /**
     * Name of the inferred-metagraph file of a history validated by this
     * validator: {@code metagraph-<shacl|rdfs|owl>-infered.ttl}, located
     * next to the history's {@code provenance.ttl}.
     */
    public String metagraphFileNameOf() {
        return "metagraph-" + language.name().toLowerCase(Locale.ROOT) + "-infered.ttl";
    }

    /**
     * Writes the statements derived by the metagraph rules into
     * {@code <directory>/metagraph-<shacl|rdfs|owl>-infered.ttl}: a {@code #}
     * comment header followed by the derived statements as Turtle.
     *
     * @return the file written
     */
    public Path writeMetagraphFile(MetagraphReport report, Path directory) throws IOException {
        Files.createDirectories(directory);
        StringBuilder sb = new StringBuilder();
        sb.append("# ===== Inferred metagraph export (Turtle) =====\n");
        sb.append("# derived by the metagraph rules from ").append(ProvOReader.PROVENANCE_FILE)
                .append(" + the per-version mg:valid verdicts\n");
        sb.append("# verdict rule language: ").append(language)
                .append(" (").append(language.getAssumption()).append(" regime)\n");
        sb.append("# derived statements: ").append(report.derived().size()).append('\n');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFDataMgr.write(out, report.derived(), Lang.TURTLE);
        sb.append(out.toString(StandardCharsets.UTF_8));
        Path file = directory.resolve(metagraphFileNameOf());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
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
