package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * <b>Inference validation</b> command-line program: given a set of rules
 * (SHACL shapes, an RDFS or an OWL ontology), checks the validity of
 * <b>all versions</b> of an exported version history and classifies every
 * <b>merge</b> by the outcome taxonomy of
 * {@code Version-history-inference-validation.md} §8 (preserved, emergent
 * violation, repaired, inherited violation).
 * <p>
 * The history is read back from an export directory written by {@link Main}:
 * the PROV-O description ({@code provenance.ttl}) plus one N-Quads file per
 * version, reloaded with {@link ProvOReader}. If the given directory does not
 * itself contain a provenance file, each of its sub-directories that does
 * (the per-policy layout written by {@link Main}) is validated in turn.
 * <p>
 * The rule language decides the regime and the world assumption (§5–§6):
 * SHACL shapes are validated closed-world, RDFS/OWL ontologies are checked
 * for open-world logical consistency. Example rule files live in
 * {@code src/main/resources/rules/}.
 * <p>
 * Under the RDFS/OWL entailment regimes the program also materializes, next
 * to each version file {@code <id>.nq}, the <b>inferred knowledge</b> of the
 * version as {@code <id>-<rdfs|owl>-infered.nq}
 * (see {@link InferenceValidator#writeInferredFiles}). The optional
 * {@code --policy} parameter restricts the run to the histories whose global
 * merge policy — read from {@code provenance.ttl} — is the given one.
 */
public class InferenceValidationMain {

    private static final String USAGE = """
            Usage: benchmark.versioning.InferenceValidationMain --rules <file> [options]
              --rules <file>     rule set: SHACL shapes or an RDFS/OWL ontology (Turtle/RDF)
              --language <lang>  shacl | rdfs | owl | auto (default auto: detected from the rules)
              --dir <dir>        directory to validate (default versions-export): either it contains
                                 provenance.ttl + <id>.nq files, or each of its sub-directories does
                                 (the per-policy layout written by benchmark.versioning.Main)
              --policy <p>       union | intersection | symmetric-difference: only validate the
                                 histories whose global merge policy (read from provenance.ttl)
                                 is <p> (default: validate every history found)
            For an RDFS/OWL rule set, the inferred knowledge of every version <id>.nq is also
            materialized next to it as <id>-<rdfs|owl>-infered.nq.
            Exit code: 0 = every version valid, 1 = at least one violation, 2 = usage error.""";

    public static void main(String[] args) throws IOException {
        System.exit(run(args));
    }

    /**
     * The whole program as a testable method.
     *
     * @return the exit code: 0 if every validated version is valid, 1 if at
     *         least one is invalid, 2 on a usage error
     */
    static int run(String[] args) throws IOException {
        Path rulesFile = null;
        RuleLanguage language = null;
        Path dir = Path.of(Main.DEFAULT_EXPORT_DIR);
        MergePolicy policy = null;
        try {
            for (int i = 0; i < args.length; i += 2) {
                String option = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for option " + option);
                }
                String value = args[i + 1];
                switch (option) {
                    case "--rules" -> rulesFile = Path.of(value);
                    case "--language" -> language = parseLanguage(value);
                    case "--dir" -> dir = Path.of(value);
                    case "--policy" -> policy = parsePolicy(value);
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            if (rulesFile == null) {
                throw new IllegalArgumentException("--rules <file> is required");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            return 2;
        }

        InferenceValidator validator = language == null
                ? InferenceValidator.fromFile(rulesFile)
                : InferenceValidator.fromFile(rulesFile, language);
        System.out.println("Rule set: " + rulesFile);
        System.out.println("Language: " + validator.getLanguage()
                + " (" + validator.getLanguage().getAssumption() + " regime)");
        if (policy != null) {
            System.out.println("Policy filter: " + policy);
        }

        boolean allValid = true;
        int validated = 0;
        for (Path target : exportDirectories(dir)) {
            ProvOReader.ProvenanceGraph loaded = ProvOReader.read(target);
            if (policy != null && loaded.policy() != policy) {
                continue;
            }
            validated++;
            allValid &= validateDirectory(validator, target, loaded);
        }
        if (validated == 0) {
            System.err.println("Error: no history with merge policy " + policy
                    + " found under " + dir);
            return 2;
        }
        return allValid ? 0 : 1;
    }

    /**
     * Parses a {@code --language} value; {@code auto} means detection from
     * the rules file (represented as {@code null}).
     */
    static RuleLanguage parseLanguage(String value) {
        if (value.equalsIgnoreCase("auto")) {
            return null;
        }
        try {
            return RuleLanguage.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid rule language '" + value
                    + "' (expected shacl, rdfs, owl or auto)");
        }
    }

    /**
     * Parses a {@code --policy} value: the {@link MergePolicy} name,
     * case-insensitive, with {@code -} or {@code _} as the separator
     * (e.g. {@code union}, {@code symmetric-difference}).
     */
    static MergePolicy parsePolicy(String value) {
        try {
            return MergePolicy.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid merge policy '" + value
                    + "' (expected union, intersection or symmetric-difference)");
        }
    }

    /**
     * The directories to validate: the given directory if it contains a
     * provenance file, otherwise its sub-directories that do.
     */
    private static List<Path> exportDirectories(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve(ProvOReader.PROVENANCE_FILE))) {
            return List.of(dir);
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }
        List<Path> targets = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(child -> Files.isRegularFile(child.resolve(ProvOReader.PROVENANCE_FILE)))
                    .sorted()
                    .forEach(targets::add);
        }
        if (targets.isEmpty()) {
            throw new IOException("No " + ProvOReader.PROVENANCE_FILE + " found in " + dir
                    + " or its sub-directories (run benchmark.versioning.Main first)");
        }
        return targets;
    }

    /**
     * Validates one reloaded export directory; prints the per-version
     * verdicts, the merge classification and the summary. Under the RDFS/OWL
     * entailment regimes, also materializes the inferred knowledge of every
     * version as {@code <id>-<rdfs|owl>-infered.nq} next to its
     * {@code <id>.nq} file.
     *
     * @return {@code true} if every version of the directory is valid.
     */
    private static boolean validateDirectory(InferenceValidator validator, Path directory,
                                             ProvOReader.ProvenanceGraph loaded) throws IOException {
        InferenceValidator.HistoryReport report = validator.validateHistory(loaded.versions());

        System.out.println("\n=== " + directory + " (policy " + loaded.policy() + ") ===");
        List<Version> ordered = VersionGraphWriter.topologicalOrder(loaded.versions());
        for (Version v : ordered) {
            InferenceValidator.VersionValidity validity = report.versions().stream()
                    .filter(x -> x.versionId().equals(v.getId()))
                    .findFirst().orElseThrow();
            System.out.printf("  %-6s %-22s %4d quads  %s%n",
                    v.getId(), kindOf(v), v.getData().size(),
                    validity.valid() ? "valid" : "INVALID (" + validity.violations().size() + " violation(s))");
            for (String violation : validity.violations()) {
                System.out.println("         - " + violation);
            }
        }

        if (!report.merges().isEmpty()) {
            System.out.println("  Merge classification:");
            for (InferenceValidator.MergeAssessment m : report.merges()) {
                System.out.println("    " + m.mergeId() + " = " + loaded.policy()
                        + "(" + String.join(", ", m.parentIds()) + "): "
                        + (m.invalidParentIds().isEmpty()
                                ? "parents valid"
                                : "invalid parent(s) " + String.join(", ", m.invalidParentIds()))
                        + ", merge " + (m.mergeValid() ? "valid" : "invalid")
                        + " -> " + m.outcome());
            }
        }
        if (validator.supportsInference()) {
            List<Path> inferredFiles = validator.writeInferredFiles(loaded.versions(), directory);
            System.out.println("  Wrote " + inferredFiles.size() + " inferred-knowledge files (*-"
                    + validator.getLanguage().name().toLowerCase(Locale.ROOT)
                    + "-infered.nq) to " + directory);
        } else {
            System.out.println("  (SHACL constraint regime: nothing is entailed,"
                    + " no inferred-knowledge files written)");
        }
        System.out.println("  Summary: " + report.summary());
        return report.allValid();
    }

    private static String kindOf(Version v) {
        int parents = v.getParents().size();
        if (parents == 0) {
            return "root";
        }
        return parents == 1 ? "transition" : "merge (" + parents + " parents)";
    }
}
