package benchmark.versioning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a <b>version graph definition file</b> and builds the corresponding
 * {@link VersionGraph} under a given global merge policy.
 * <p>
 * The file format is line-based; {@code #} starts a comment and blank lines
 * are ignored. The directives are:
 * <pre>
 *   root &lt;id&gt;                                    root node (|pre(v)| = 0)
 *   transition &lt;id&gt; &lt;parentId&gt;                   transition node (|pre(v)| = 1)
 *   merge &lt;id&gt; &lt;parent&gt; &lt;parent&gt; [&lt;parent&gt; ...] merge node (|pre(v)| &ge; 2)
 *   add &lt;subject&gt; &lt;predicate&gt; &lt;object&gt; &lt;graphName&gt;
 *   delete &lt;subject&gt; &lt;predicate&gt; &lt;object&gt; &lt;graphName&gt;
 * </pre>
 * {@code add}/{@code delete} lines apply to the most recent {@code root} or
 * {@code transition} directive ({@code delete} is not allowed for a root).
 * The state of merge nodes is never given in the file: it is computed by
 * applying the global policy to the parents' datasets, as required by the
 * formal model.
 * <p>
 * The same definition file can therefore be replayed under any
 * {@link MergePolicy}: only the merge states differ.
 */
public final class VersionGraphReader {

    private VersionGraphReader() {
        // utility class
    }

    /**
     * Reads the version graph definition file and builds the graph under
     * the given global merge policy.
     */
    public static VersionGraph read(Path file, MergePolicy policy) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        return parse(lines, policy, file.toString());
    }

    /**
     * Parses the given definition lines and builds the graph under the given
     * global merge policy. {@code source} is only used in error messages.
     */
    public static VersionGraph parse(List<String> lines, MergePolicy policy, String source) {
        VersionGraph graph = new VersionGraph(policy);
        Map<String, Version> byId = new HashMap<>();
        PendingNode pending = null;

        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            String directive = tokens[0].toLowerCase(Locale.ROOT);
            switch (directive) {
                case "root" -> {
                    flush(graph, byId, pending);
                    require(tokens.length == 2, source, lineNo, "expected: root <id>");
                    pending = PendingNode.root(tokens[1], lineNo);
                }
                case "transition" -> {
                    flush(graph, byId, pending);
                    require(tokens.length == 3, source, lineNo, "expected: transition <id> <parentId>");
                    pending = PendingNode.transition(tokens[1], tokens[2], lineNo);
                }
                case "merge" -> {
                    flush(graph, byId, pending);
                    pending = null;
                    require(tokens.length >= 4, source, lineNo,
                            "expected: merge <id> <parent> <parent> [<parent> ...]");
                    List<Version> parents = new ArrayList<>();
                    for (int i = 2; i < tokens.length; i++) {
                        parents.add(resolve(byId, tokens[i], source, lineNo));
                    }
                    Version merge = graph.createMerge(tokens[1], parents);
                    byId.put(merge.getId(), merge);
                }
                case "add", "delete" -> {
                    require(pending != null, source, lineNo,
                            "'" + directive + "' must follow a 'root' or 'transition' directive");
                    Quad quad = parseQuad(tokens, source, lineNo);
                    if (directive.equals("add")) {
                        pending.additions.add(quad);
                    } else {
                        require(pending.parentId != null, source, lineNo,
                                "'delete' is not allowed for a root node");
                        pending.deletions.add(quad);
                    }
                }
                default -> throw error(source, lineNo, "unknown directive '" + tokens[0] + "'");
            }
        }
        flush(graph, byId, pending);
        return graph;
    }

    /**
     * Creates the pending root/transition node in the graph, if any.
     */
    private static void flush(VersionGraph graph, Map<String, Version> byId, PendingNode pending) {
        if (pending == null) {
            return;
        }
        Version v;
        if (pending.parentId == null) {
            v = graph.createRoot(pending.id, pending.additions);
        } else {
            Version parent = resolve(byId, pending.parentId, null, pending.lineNo);
            v = graph.createTransition(pending.id, parent, pending.additions, pending.deletions);
        }
        byId.put(v.getId(), v);
    }

    /**
     * A quad line is: {@code add|delete <subject> <predicate> <object> <graphName>}.
     * The object may contain spaces (e.g. a quoted literal): it spans all the
     * tokens between the predicate and the graph name (the last token).
     */
    private static Quad parseQuad(String[] tokens, String source, int lineNo) {
        require(tokens.length >= 5, source, lineNo,
                "expected: " + tokens[0] + " <subject> <predicate> <object> <graphName>");
        String subject = tokens[1];
        String predicate = tokens[2];
        String graphName = tokens[tokens.length - 1];
        String object = String.join(" ", List.of(tokens).subList(3, tokens.length - 1));
        return new Quad(subject, predicate, object, graphName);
    }

    private static Version resolve(Map<String, Version> byId, String id, String source, int lineNo) {
        Version v = byId.get(id);
        if (v == null) {
            throw error(source, lineNo, "unknown version id '" + id + "' (a version must be declared before it is referenced)");
        }
        return v;
    }

    /**
     * Removes an end-of-line comment: everything from a {@code #} that is at
     * the start of the line or preceded by whitespace.
     */
    private static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static void require(boolean condition, String source, int lineNo, String message) {
        if (!condition) {
            throw error(source, lineNo, message);
        }
    }

    private static IllegalArgumentException error(String source, int lineNo, String message) {
        String location = (source == null ? "" : source + ", ") + "line " + lineNo;
        return new IllegalArgumentException("Invalid version graph definition (" + location + "): " + message);
    }

    /**
     * A root or transition node being accumulated: its quads ({@code add} /
     * {@code delete} lines) are buffered until the next directive.
     */
    private static final class PendingNode {
        final String id;
        final String parentId; // null for a root node
        final int lineNo;
        final Set<Quad> additions = new HashSet<>();
        final Set<Quad> deletions = new HashSet<>();

        private PendingNode(String id, String parentId, int lineNo) {
            this.id = id;
            this.parentId = parentId;
            this.lineNo = lineNo;
        }

        static PendingNode root(String id, int lineNo) {
            return new PendingNode(id, null, lineNo);
        }

        static PendingNode transition(String id, String parentId, int lineNo) {
            return new PendingNode(id, parentId, lineNo);
        }
    }
}
