package benchmark.versioning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 */
public class InferenceValidationMain {

    private static final String USAGE = """
            Usage: benchmark.versioning.InferenceValidationMain --rules <file> [options]
              --rules <file>     rule set: SHACL shapes or an RDFS/OWL ontology (Turtle/RDF)
              --language <lang>  shacl | rdfs | owl | auto (default auto: detected from the rules)
              --dir <dir>        directory to validate (default versions-export): either it contains
                                 provenance.ttl + <id>.nq files, or each of its sub-directories does
                                 (the per-policy layout written by benchmark.versioning.Main)
            Exit code: 0 = every version valid, 1 = at least one violation, 2 = usage error.""";

    public static void main(String[] args) throws IOException {
        Path rulesFile = null;
        RuleLanguage language = null;
        Path dir = Path.of(Main.DEFAULT_EXPORT_DIR);
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
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            if (rulesFile == null) {
                throw new IllegalArgumentException("--rules <file> is required");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        InferenceValidator validator = language == null
                ? InferenceValidator.fromFile(rulesFile)
                : InferenceValidator.fromFile(rulesFile, language);
        System.out.println("Rule set: " + rulesFile);
        System.out.println("Language: " + validator.getLanguage()
                + " (" + validator.getLanguage().getAssumption() + " regime)");

        List<Path> targets = exportDirectories(dir);
        boolean allValid = true;
        for (Path target : targets) {
            allValid &= validateDirectory(validator, target);
        }
        System.exit(allValid ? 0 : 1);
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
     * Reloads and validates one export directory; prints the per-version
     * verdicts, the merge classification and the summary.
     *
     * @return {@code true} if every version of the directory is valid.
     */
    private static boolean validateDirectory(InferenceValidator validator, Path directory) throws IOException {
        ProvOReader.ProvenanceGraph loaded = ProvOReader.read(directory);
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
