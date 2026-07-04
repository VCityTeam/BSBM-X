package benchmark.versioning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;

/**
 * Writes the versions of a version graph to disk. The RDF dataset S(v) of
 * each version is serialized as <b>N-Quads by Apache Jena</b>
 * ({@code <subject> <predicate> <object> <graphName> .} with full IRIs),
 * so the per-version files can be parsed back with Jena
 * (see {@link ProvOReader}).
 * Versions are written in topological order (parents before children) and
 * the N-Quads lines are sorted, so the export is deterministic and
 * diff-friendly. A short {@code #} comment header (skipped by the Jena
 * N-Quads parser on read) documents each version.
 */
public final class VersionGraphWriter {

    private VersionGraphWriter() {
        // utility class
    }

    /**
     * Writes <b>each version in a different file</b> inside the given
     * directory: one N-Quads file per version, named {@code <versionId>.nq}
     * (the version id is sanitized so it is a safe file name).
     * <p>
     * Each file contains a small {@code #} comment header (version id, global
     * merge policy, kind, parents, quad count) followed by the full RDF
     * dataset S(v) as sorted N-Quads lines.
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
     * Serializes a single version to the content of its dedicated N-Quads
     * file: a {@code #} comment header followed by the sorted N-Quads lines
     * of its RDF dataset.
     */
    public static String serializeVersion(Version v, MergePolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ===== RDF version export (N-Quads) =====\n");
        sb.append("# version: ").append(v.getId()).append('\n');
        sb.append("# global merge policy: ").append(policy == null ? "(per merge)" : policy).append('\n');
        sb.append("# kind: ").append(kindOf(v)).append('\n');
        if (v.getMergePolicy() != null) {
            sb.append("# merge policy: ").append(v.getMergePolicy()).append('\n');
        }
        sb.append("# parents: ").append(parentsOf(v)).append('\n');
        sb.append("# quads: ").append(v.getData().size()).append('\n');
        for (String line : nquadLines(v.getData())) {
            sb.append(line).append('\n');
        }
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
        return sanitize(versionId) + ".nq";
    }

    /**
     * A version id sanitized to be a safe file name component (shared by all
     * the files derived from a version, e.g. the inferred-knowledge files of
     * {@link InferenceValidator#writeInferredFiles}).
     */
    static String sanitize(String versionId) {
        return versionId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Serializes a set of quads to sorted N-Quads lines using Apache Jena.
     */
    static List<String> nquadLines(Set<Quad> quads) {
        DatasetGraph dsg = DatasetGraphFactory.createGeneral();
        for (Quad q : quads) {
            dsg.add(q);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFDataMgr.write(out, dsg, Lang.NQUADS);
        return out.toString(StandardCharsets.UTF_8).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .sorted()
                .collect(Collectors.toList());
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

    private static String parentsOf(Version v) {
        return v.getParents().isEmpty()
                ? "(none)"
                : v.getParents().stream().map(Version::getId).collect(Collectors.joining(", "));
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
