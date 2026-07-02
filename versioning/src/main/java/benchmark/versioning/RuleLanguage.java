package benchmark.versioning;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;

/**
 * The rule languages understood by {@link InferenceValidator}, each tied to
 * the world assumption under which it evaluates a version (see
 * {@code Version-history-inference-validation.md}, §5–§6):
 * <ul>
 *   <li>{@link #SHACL} — constraint regime, <b>closed-world</b>: a statement
 *       absent from the dataset is false, so loss-type damage (missing
 *       witnesses, dangling references) is detectable;</li>
 *   <li>{@link #RDFS} — entailment regime, <b>open-world</b>: absence is
 *       unknown; the only detectable inconsistencies are datatype clashes
 *       under D-entailment;</li>
 *   <li>{@link #OWL} — entailment regime, <b>open-world</b>: negative axioms
 *       (disjointness, functional properties, cardinality) make conflicts
 *       detectable as logical inconsistency.</li>
 * </ul>
 */
public enum RuleLanguage {
    SHACL(WorldAssumption.CLOSED),
    RDFS(WorldAssumption.OPEN),
    OWL(WorldAssumption.OPEN);

    /** The world assumption under which a rule language reads a dataset. */
    public enum WorldAssumption {
        /** Absence of a statement is falsity (constraint validation). */
        CLOSED("closed-world"),
        /** Absence of a statement is unknown (logical consistency). */
        OPEN("open-world");

        private final String label;

        WorldAssumption(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The W3C SHACL namespace. */
    public static final String SHACL_NS = "http://www.w3.org/ns/shacl#";
    /** The W3C OWL namespace. */
    public static final String OWL_NS = "http://www.w3.org/2002/07/owl#";

    private final WorldAssumption assumption;

    RuleLanguage(WorldAssumption assumption) {
        this.assumption = assumption;
    }

    public WorldAssumption getAssumption() {
        return assumption;
    }

    /**
     * Detects the language of a rule set from the namespaces it uses: SHACL
     * if any term of the SHACL namespace appears, otherwise OWL if any term
     * of the OWL namespace appears, otherwise RDFS.
     */
    public static RuleLanguage detect(Model rules) {
        boolean owl = false;
        StmtIterator it = rules.listStatements();
        while (it.hasNext()) {
            Statement st = it.next();
            if (usesNamespace(st, SHACL_NS)) {
                return SHACL;
            }
            owl = owl || usesNamespace(st, OWL_NS);
        }
        return owl ? OWL : RDFS;
    }

    private static boolean usesNamespace(Statement st, String ns) {
        if (st.getPredicate().getURI().startsWith(ns)) {
            return true;
        }
        RDFNode object = st.getObject();
        return object.isURIResource() && object.asResource().getURI().startsWith(ns);
    }
}
