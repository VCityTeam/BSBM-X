package benchmark.versioning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes all the versions of a version graph into a specific file.
 * <p>
 * The output is a plain-text, human-readable export: a small header
 * (global merge policy, number of versions) followed by one section per
 * version listing its kind (root / transition / merge), its parents
 * pre(v) and its full RDF dataset S(v) as N-Quads-style lines
 * ({@code subject predicate object graphName .}).
 * <p>
 * Versions are written in topological order (parents before children) and
 * quads are sorted, so the export is deterministic and diff-friendly.
 */
public final class VersionGraphWriter {

    private VersionGraphWriter() {
        // utility class
    }

    /**
     * Writes all the versions of the graph into the given file,
     * overwriting it if it already exists.
     */
    public static void writeToFile(VersionGraph graph, Path file) throws IOException {
        writeToFile(graph.getVersions(), graph.getGlobalPolicy(), file);
    }

    /**
     * Writes all the given versions into the given file,
     * overwriting it if it already exists.
     */
    public static void writeToFile(Collection<Version> versions, MergePolicy policy, Path file) throws IOException {
        write(serialize(versions, policy), file, false);
    }

    /**
     * Appends all the versions of the graph to the given file
     * (useful to export several graphs into the same file).
     */
    public static void appendToFile(VersionGraph graph, Path file) throws IOException {
        appendToFile(graph.getVersions(), graph.getGlobalPolicy(), file);
    }

    /**
     * Appends all the given versions to the given file.
     */
    public static void appendToFile(Collection<Version> versions, MergePolicy policy, Path file) throws IOException {
        write(serialize(versions, policy), file, true);
    }

    /**
     * Writes <b>each version in a different file</b> inside the given
     * directory: one file per version, named {@code <versionId>.nq}
     * (the version id is sanitized so it is a safe file name).
     * <p>
     * Each file contains a small comment header (version id, global merge
     * policy, kind, parents, quad count) followed by the full RDF dataset
     * S(v) of the version as sorted N-Quads-style lines.
     *
     * @return the list of files written, in topological order.
     */
    public static List<Path> writeEachVersionToDirectory(VersionGraph graph, Path directory) throws IOException {
        return writeEachVersionToDirectory(graph.getVersions(), graph.getGlobalPolicy(), directory);
    }

    /**
     * Writes each of the given versions in a different file inside the
     * given directory (one file per version).
     *
     * @return the list of files written, in topological order.
     */
    public static List<Path> writeEachVersionToDirectory(Collection<Version> versions, MergePolicy policy, Path directory) throws IOException {
        Files.createDirectories(directory);
        List<Path> written = new ArrayList<>();
        for (Version v : topologicalOrder(versions)) {
            Path file = directory.resolve(fileNameOf(v));
            Files.writeString(file, serializeVersion(v, policy), StandardCharsets.UTF_8);
            written.add(file);
        }
        return written;
    }

    /**
     * Serializes a single version to the content of its dedicated file.
     */
    public static String serializeVersion(Version v, MergePolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ===== RDF version export =====\n");
        sb.append("# version: ").append(v.getId()).append('\n');
        sb.append("# global merge policy: ").append(policy).append('\n');
        sb.append("# kind: ").append(kindOf(v)).append('\n');
        sb.append("# parents: ").append(v.getParents().isEmpty()
                ? "(none)"
                : v.getParents().stream().map(Version::getId).collect(Collectors.joining(", ")))
                .append('\n');
        sb.append("# quads: ").append(v.getData().size()).append('\n');
        v.getData().stream()
                .map(VersionGraphWriter::toLine)
                .sorted()
                .forEach(line -> sb.append(line).append('\n'));
        return sb.toString();
    }

    /**
     * File name of the dedicated file of a version: the version id,
     * sanitized to be a safe file name, with the {@code .nq} extension.
     */
    public static String fileNameOf(Version v) {
        return fileNameOf(v.getId());
    }

    /**
     * File name of the dedicated file of the version with the given id.
     */
    public static String fileNameOf(String versionId) {
        return versionId.replaceAll("[^A-Za-z0-9._-]", "_") + ".nq";
    }

    /**
     * Serializes all the versions of the graph to the export text format.
     */
    public static String serialize(VersionGraph graph) {
        return serialize(graph.getVersions(), graph.getGlobalPolicy());
    }

    /**
     * Serializes all the given versions to the export text format.
     */
    public static String serialize(Collection<Version> versions, MergePolicy policy) {
        List<Version> ordered = topologicalOrder(versions);
        StringBuilder sb = new StringBuilder();
        sb.append("# ===== RDF version graph export =====\n");
        sb.append("# Global merge policy: ").append(policy).append('\n');
        sb.append("# Number of versions: ").append(ordered.size()).append('\n');
        for (Version v : ordered) {
            sb.append('\n');
            sb.append("=== Version ").append(v.getId()).append(" ===\n");
            sb.append("kind: ").append(kindOf(v)).append('\n');
            sb.append("parents: ").append(v.getParents().isEmpty()
                    ? "(none)"
                    : v.getParents().stream().map(Version::getId).collect(Collectors.joining(", ")))
                    .append('\n');
            sb.append("quads: ").append(v.getData().size()).append('\n');
            v.getData().stream()
                    .map(VersionGraphWriter::toLine)
                    .sorted()
                    .forEach(line -> sb.append(line).append('\n'));
        }
        return sb.toString();
    }

    private static void write(String content, Path file, boolean append) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (append) {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }

    private static String kindOf(Version v) {
        int parents = v.getParents().size();
        if (parents == 0) {
            return "root";
        }
        if (parents == 1) {
            return "transition";
        }
        return "merge (" + parents + " parents)";
    }

    private static String toLine(Quad q) {
        return q.getSubject() + " " + q.getPredicate() + " " + q.getObject() + " " + q.getGraphName() + " .";
    }

    /**
     * Orders the versions so that every parent appears before its children
     * (Kahn's algorithm). Versions with the same depth are ordered by id so
     * that the export is deterministic. Parents that are not part of the
     * given collection are ignored.
     */
    static List<Version> topologicalOrder(Collection<Version> versions) {
        Set<Version> all = new HashSet<>(versions);
        Map<Version, Integer> remainingParents = new HashMap<>();
        Map<Version, List<Version>> children = new HashMap<>();
        for (Version v : all) {
            int count = 0;
            for (Version parent : v.getParents()) {
                if (all.contains(parent)) {
                    count++;
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(v);
                }
            }
            remainingParents.put(v, count);
        }

        Deque<Version> ready = all.stream()
                .filter(v -> remainingParents.get(v) == 0)
                .sorted(Comparator.comparing(Version::getId))
                .collect(Collectors.toCollection(ArrayDeque::new));

        List<Version> ordered = new ArrayList<>(all.size());
        while (!ready.isEmpty()) {
            Version v = ready.removeFirst();
            ordered.add(v);
            List<Version> next = new ArrayList<>();
            for (Version child : children.getOrDefault(v, List.of())) {
                int remaining = remainingParents.merge(child, -1, Integer::sum);
                if (remaining == 0) {
                    next.add(child);
                }
            }
            next.sort(Comparator.comparing(Version::getId));
            next.forEach(ready::addLast);
        }
        return ordered;
    }
}
