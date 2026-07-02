# Versioning — RDF Version Graph with a Global Merge Policy

This package (`benchmark.versioning`) implements the mathematical formalization of a
**version graph** modeled as a Directed Acyclic Graph (DAG), where:

- every version holds an **RDF dataset** made of **quads** (subject, predicate, object, graph name),
- the graph supports **multiple branches** and **n-ary merges** ("octopus" merges),
- all merges are governed by a single **global merge policy** `⊕ ∈ {∪, ∩, Δ}`,
- graph **consistency** can be verified: every merge node must satisfy
  `S(v) = ⊕ S(u), u ∈ pre(v)`.

---

## 1. Building and running

The package is built with **Maven** (JDK 17+ required) and depends on
**Apache Jena** (`jena-arq`), used to parse the generated PROV-O `provenance.ttl`.
The `pom.xml` at the project root only builds this package; the rest of the BSBM
sources keep the legacy Ant build (`build.xml`).

From the project root (`BSBM/`):

```bash
# Compile
mvn compile

# Run the demonstration program
mvn compile exec:java

# Optionally choose the directory where the versions are exported
mvn compile exec:java -Dexec.args="my-export-dir"

# Optionally also choose the version graph definition file to load
mvn compile exec:java -Dexec.args="my-export-dir my-version-graph.txt"
```

The demo graph is **not hard-coded**: it is read from a version graph definition file
(`src/benchmark/versioning/version-graph.txt` by default, or the file given as second
argument) — see §3.10.

For each policy (`UNION`, `INTERSECTION`, `SYMMETRIC_DIFFERENCE`) the demo:

1. builds the graph from the definition file,
2. **exports each version in a different file** and generates the **PROV-O graph
   describing the version graph** (`provenance.ttl`),
3. **reloads the whole version graph from the generated `provenance.ttl`** (parsed with
   Apache Jena — see §3.11): the DAG structure and the policy come from the PROV-O
   description, the dataset S(v) of each version from its exported file,
4. verifies that the round-trip is lossless and prints `Graph is consistent: true`
   for the reloaded graph.

The last section demonstrates the detection of a tampered merge, also through the
PROV-O round-trip (`Tampered graph is consistent: false`).
The export directory (`versions-export/` by default, or the directory given as first
argument) contains one sub-directory per demo policy:

```
versions-export/
├── union/
│   ├── V0.nq  V1.nq  V2.nq  V3.nq  M1.nq  V4.nq  M2.nq   # one file per version
│   └── provenance.ttl                                     # PROV-O description of the DAG
├── intersection/            (same layout)
├── symmetric-difference/    (same layout)
└── tampered/                (the negative test: a tampered merge, detected as inconsistent)
```

---

## 2. Classes overview

| Class | Role |
|---|---|
| `Quad` | An immutable RDF quad `(subject, predicate, object, graphName)`. The dataset of a version is a `Set<Quad>`. |
| `Version` | A node `v ∈ V` of the DAG: an id, an immutable RDF dataset `S(v)`, and the list of its parents `pre(v)`. |
| `MergePolicy` | The global operator `⊕`: `UNION`, `INTERSECTION` or `SYMMETRIC_DIFFERENCE`. Commutative and associative, hence n-ary merges are well defined. |
| `VersionGraph` | Builds the DAG: root nodes, transition nodes (diff of additions/deletions) and merge nodes (strict application of `⊕`). |
| `VersionConsistencyChecker` | Verifies the strict consistency of a graph (or of an arbitrary collection of versions) under a policy. |
| `VersionGraphWriter` | Writes the versions of a graph (or of an arbitrary collection of versions) to disk: all inside a single file, or **each version in a different file**. |
| `VersionGraphReader` | **Reads a version graph definition file** and builds the `VersionGraph` under a given policy (used by `Main` to build the demo graph instead of hard-coding it). |
| `ProvOWriter` | Generates an **RDF graph describing the version graph using the W3C PROV-O ontology** (Turtle). |
| `ProvOReader` | **Reads a version graph back from its PROV-O description** (`provenance.ttl`, parsed with Apache Jena) and the per-version export files (used by `Main.testPolicy` for the consistency check). |
| `Main` | Runnable demonstration of all features. |

### About named graphs

The `graphName` of a `Quad` is an IRI identifying the **named graph** the triple belongs to
inside the RDF dataset. It is **deliberately independent of the version identifiers**:
several versions may contain quads of the same named graph, and one version may span several
named graphs. Use `Version.getNamedGraphs()` to view a version's dataset grouped by graph.

---

## 3. Usage guide

### 3.1. Choose the global policy and create the graph

The policy is chosen **once**, globally, when the graph is created. It is applied to every
merge in the graph.

```java
VersionGraph graph = new VersionGraph(MergePolicy.UNION);
// or MergePolicy.INTERSECTION, MergePolicy.SYMMETRIC_DIFFERENCE
```

### 3.2. Create a root node (Case 1: |pre(v)| = 0)

A root is an origin of the history. Its state is the initial RDF dataset (possibly empty).

```java
Set<Quad> initialData = Set.of(
        new Quad("ex:product1", "rdf:type",   "bsbm:Product", "http://example.org/graph/products"),
        new Quad("ex:offer1",   "bsbm:price", "\"42.0\"",     "http://example.org/graph/offers"));

Version v0 = graph.createRoot("V0", initialData);
```

### 3.3. Create transition nodes (Case 2: |pre(v)| = 1) — branching

A transition is a standard commit: `S(v) = (S(u) \ Deletions) ∪ Additions`.
**Several transitions may share the same parent — this is how branches are created.**

```java
// Branch 1: add a review, remove the offer
Version v1 = graph.createTransition("V1", v0,
        Set.of(new Quad("ex:review1", "bsbm:reviewFor", "ex:product1", "http://example.org/graph/reviews")),
        Set.of(new Quad("ex:offer1",  "bsbm:price",     "\"42.0\"",    "http://example.org/graph/offers")));

// Branch 2: also starts from v0
Version v2 = graph.createTransition("V2", v0,
        Set.of(new Quad("ex:product2", "rdf:type", "bsbm:Product", "http://example.org/graph/products")),
        Set.of());
```

### 3.4. Create merge nodes (Case 3: |pre(v)| ≥ 2)

The state of a merge is **strictly** the result of the global policy applied to the parents'
datasets — no additions or deletions are allowed during a merge. Any number of parents ≥ 2 is
supported (octopus merge).

```java
// Binary merge
Version m1 = graph.createMerge("M1", List.of(v1, v2));

// Octopus merge (3 parents or more)
Version m2 = graph.createMerge("M2", List.of(v1, v2, v0));
```

`createMerge` throws `IllegalArgumentException` if fewer than 2 parents are given.
All creation methods throw `IllegalArgumentException` if the version id already exists.

### 3.5. Verify graph consistency

A graph is **strictly consistent** iff every merge node's state equals the policy applied to
its parents' states:

```java
boolean ok = VersionConsistencyChecker.isConsistent(graph);
```

You can also check an arbitrary collection of versions against a policy (useful to audit
versions that were built outside a `VersionGraph`, e.g. loaded from storage):

```java
boolean ok = VersionConsistencyChecker.isConsistent(versionsCollection, MergePolicy.UNION);
```

When a violation is found, the checker prints a `[DEBUG_LOG]` message with the offending
version id, the expected dataset and the actual dataset, and returns `false`.

### 3.6. Inspect a version

```java
version.getId();          // the version identifier
version.getData();        // Set<Quad> — the full RDF dataset S(v) (immutable)
version.getParents();     // List<Version> — pre(v)
version.getNamedGraphs(); // Map<String, Set<Quad>> — dataset grouped by named graph
```

### 3.7. Write all versions inside a specific file

`VersionGraphWriter` serializes **every version of the graph** into a plain-text,
human-readable export file:

```java
import java.nio.file.Path;

// Overwrite the file with all the versions of the graph
VersionGraphWriter.writeToFile(graph, Path.of("versions-export.txt"));

// Append another graph (or another collection of versions) to the same file
VersionGraphWriter.appendToFile(otherGraph, Path.of("versions-export.txt"));

// Also works with an arbitrary collection of versions and a policy
VersionGraphWriter.writeToFile(versionsCollection, MergePolicy.UNION, Path.of("audit.txt"));

// Or get the serialized text without touching the file system
String text = VersionGraphWriter.serialize(graph);
```

The export starts with a header (global merge policy, number of versions) followed by one
section per version listing its kind (`root`, `transition` or `merge`), its parents `pre(v)`
and its full RDF dataset `S(v)` as N-Quads-style lines (`subject predicate object graphName .`).
Versions are written in **topological order** (parents before children) and quads are sorted,
so the export is deterministic and diff-friendly. Missing parent directories of the target
file are created automatically.

Example of an exported version section:

```
=== Version M1 ===
kind: merge (2 parents)
parents: V1, V2
quads: 5
ex:offer1 bsbm:price "42.0" http://example.org/graph/offers .
ex:product1 rdf:type bsbm:Product http://example.org/graph/products .
...
```

### 3.8. Write each version in a different file

`VersionGraphWriter.writeEachVersionToDirectory` writes **one file per version** inside a
directory. Each file is named after the version id (sanitized), with the `.nq` extension:
`V0.nq`, `M1.nq`, …

```java
import java.nio.file.Path;

// One file per version, inside the directory (created if missing).
// Returns the list of files written, in topological order.
List<Path> files = VersionGraphWriter.writeEachVersionToDirectory(graph, Path.of("export-dir"));

// Also works with an arbitrary collection of versions and a policy
VersionGraphWriter.writeEachVersionToDirectory(versionsCollection, MergePolicy.UNION, Path.of("export-dir"));

// Or serialize a single version without touching the file system
String text = VersionGraphWriter.serializeVersion(version, MergePolicy.UNION);
```

Each file starts with a comment header (`#` lines: version id, global merge policy, kind,
parents, quad count) followed by the full RDF dataset `S(v)` as sorted N-Quads-style lines.
Example of `M1.nq`:

```
# ===== RDF version export =====
# version: M1
# global merge policy: UNION
# kind: merge (2 parents)
# parents: V1, V2
# quads: 5
ex:offer1 bsbm:price "42.0" http://example.org/graph/offers .
ex:product1 rdf:type bsbm:Product http://example.org/graph/products .
...
```

If you export several graphs that reuse the same version ids (like the three demo policies),
write each graph into its **own sub-directory** to avoid file-name clashes.

### 3.9. Describe the version graph with PROV-O

`ProvOWriter` generates an RDF graph (Turtle) that **describes the version graph itself**
using the [W3C PROV-O ontology](https://www.w3.org/TR/prov-o/):

```java
import java.nio.file.Path;

// Write the PROV-O description of the graph into a Turtle file
ProvOWriter.writeToFile(graph, Path.of("provenance.ttl"));

// Also works with an arbitrary collection of versions and a policy
ProvOWriter.writeToFile(versionsCollection, MergePolicy.UNION, Path.of("provenance.ttl"));

// Or get the Turtle text without touching the file system
String ttl = ProvOWriter.serialize(graph);
```

Mapping of the formal model onto PROV-O:

| Formal model | PROV-O |
|---|---|
| Version `v ∈ V` | `ver:<id> a prov:Entity` |
| Edge `(u, v) ∈ A` | `ver:v prov:wasDerivedFrom ver:u` |
| Transition node (`\|pre(v)\| = 1`) | `act:transition-<id> a prov:Activity ; prov:used ver:u ; prov:generated ver:v` and `ver:v prov:wasGeneratedBy act:transition-<id>` |
| Merge node (`\|pre(v)\| ≥ 2`) | `act:merge-<id> a prov:Activity ; prov:used` all parents; `prov:wasAssociatedWith` the policy agent |
| Global merge policy `⊕` | `agt:policy-<POLICY> a prov:SoftwareAgent` |
| Root node (`\|pre(v)\| = 0`) | plain `prov:Entity` with no generation activity |

The IRIs minted for versions (`http://example.org/versioning/version/`), activities and
agents live in their own namespaces, distinct from the named graphs used inside the RDF
datasets `S(v)`. Excerpt of the generated Turtle:

```turtle
ver:M1 a prov:Entity ;
    rdfs:label "Version M1" ;
    rdfs:comment "merge node with 5 quad(s)" ;
    prov:wasDerivedFrom ver:V1 , ver:V2 ;
    prov:wasGeneratedBy act:merge-M1 .

act:merge-M1 a prov:Activity ;
    rdfs:label "Merge (2 parents, policy UNION) producing M1" ;
    prov:used ver:V1 , ver:V2 ;
    prov:generated ver:M1 ;
    prov:wasAssociatedWith agt:policy-UNION .
```

### 3.10. Read a version graph from a definition file

`VersionGraphReader` builds a `VersionGraph` from a **plain-text definition file**, so the
graph does not have to be hard-coded in Java. This is how `Main` builds the demo graph: it
reads `src/benchmark/versioning/version-graph.txt` (or the file given as second program
argument) and replays it under each policy.

```java
import java.nio.file.Path;

// Build the graph described in the file, under the given global policy
VersionGraph graph = VersionGraphReader.read(Path.of("version-graph.txt"), MergePolicy.UNION);

// Or parse lines already in memory (source name is only used in error messages)
VersionGraph graph2 = VersionGraphReader.parse(lines, MergePolicy.UNION, "in-memory");
```

The format is line-based; `#` starts a comment (at start of line or preceded by whitespace)
and blank lines are ignored:

| Directive | Meaning |
|---|---|
| `root <id>` | Declare a root node (`\|pre(v)\| = 0`). |
| `transition <id> <parentId>` | Declare a transition node (`\|pre(v)\| = 1`). |
| `merge <id> <parent> <parent> [<parent> ...]` | Declare a merge node (≥ 2 parents, octopus merges supported). |
| `add <subject> <predicate> <object> <graphName>` | Quad added by the pending `root`/`transition`. |
| `delete <subject> <predicate> <object> <graphName>` | Quad deleted by the pending `transition` (not allowed for a root). |

Rules:

- `add`/`delete` lines apply to the most recent `root` or `transition` directive.
- A version must be declared **before** it is referenced as a parent.
- The `<object>` may contain spaces (e.g. a quoted literal `"a label"`): it spans all the
  tokens between the predicate and the graph name (the last token of the line).
- **The state of merge nodes is never given in the file**: it is computed by applying the
  global policy to the parents' datasets, as required by the formal model. The same file can
  therefore be replayed under any policy — only the merge states differ.
- Syntax errors raise an `IllegalArgumentException` with the file name and line number.

### 3.11. Read the version graph back from its PROV-O description

`ProvOReader` (based on **Apache Jena**) reconstructs a version graph from an export
directory: the generated `provenance.ttl` is the **authoritative description of the version
graph**, and the dataset S(v) of each version is loaded from its `<id>.nq` file next to it.
This is what `Main.testPolicy` uses: the consistency check runs on the graph as described by
`provenance.ttl`, not on the in-memory build.

```java
import java.nio.file.Path;

// Reload the version graph described by <dir>/provenance.ttl; the dataset of
// each version is read from its <id>.nq file in the same directory.
ProvOReader.ProvenanceGraph loaded = ProvOReader.read(Path.of("versions-export/union"));

List<Version> versions = loaded.versions();   // parents-first order
MergePolicy policy     = loaded.policy();     // declared by the policy agent

boolean ok = VersionConsistencyChecker.isConsistent(versions, policy);
```

Mapping read from the PROV-O graph (the inverse of §3.9):

| PROV-O | Reconstructed |
|---|---|
| `ver:<id> a prov:Entity` | a `Version` with id `<id>` (recovered from the `rdfs:label "Version <id>"`, falling back to the IRI local name) |
| `ver:v prov:wasDerivedFrom ver:u` | `u ∈ pre(v)` |
| `agt:policy-<POLICY> a prov:SoftwareAgent` | the global `MergePolicy` |
| — | the dataset S(v) is loaded from `<id>.nq` in the same directory |

`ProvOReader.read` fails with an `IOException` if the provenance file, the policy agent or a
version dataset file is missing, if a version derives from an entity not described in the
file, or if the `prov:wasDerivedFrom` graph contains a cycle. Note that
`prov:wasDerivedFrom` statements have set semantics, so a merge listing the same parent
twice cannot be represented in (or read back from) PROV-O.

Example (the beginning of the demo `version-graph.txt`):

```
root V0
add ex:product1 rdf:type bsbm:Product http://example.org/graph/products
add ex:product1 rdfs:label "Widget" http://example.org/graph/products
add ex:offer1 bsbm:price "42.0" http://example.org/graph/offers

transition V1 V0
add ex:review1 bsbm:reviewFor ex:product1 http://example.org/graph/reviews
delete ex:offer1 bsbm:price "42.0" http://example.org/graph/offers

merge M1 V1 V2
```

---

## 4. Merge policy semantics

For a merge node `v` with parents `u1 … uk`:

| Policy | Formula | Meaning |
|---|---|---|
| `UNION` | `S(u1) ∪ … ∪ S(uk)` | Keep every quad present in **at least one** branch. |
| `INTERSECTION` | `S(u1) ∩ … ∩ S(uk)` | Keep only quads present in **all** branches. |
| `SYMMETRIC_DIFFERENCE` | `S(u1) Δ … Δ S(uk)` | Keep quads present in an **odd number** of branches. |

All three operators are commutative and associative, so the order of parents does not matter
and the reduction generalizes naturally to n parents. Note: the parents' datasets are passed
as a `List` (not a `Set`) so that parents with identical states are not collapsed — this is
significant for `SYMMETRIC_DIFFERENCE`.

---

## 5. Complete example

```java
import benchmark.versioning.*;
import java.util.List;
import java.util.Set;

VersionGraph graph = new VersionGraph(MergePolicy.UNION);

Version v0 = graph.createRoot("V0",
        Set.of(new Quad("ex:s", "ex:p", "ex:o", "http://example.org/graph/g1")));

Version v1 = graph.createTransition("V1", v0,
        Set.of(new Quad("ex:s2", "ex:p", "ex:o2", "http://example.org/graph/g2")),
        Set.of());

Version v2 = graph.createTransition("V2", v0,
        Set.of(new Quad("ex:s3", "ex:p", "ex:o3", "http://example.org/graph/g1")),
        Set.of());

Version merge = graph.createMerge("M", List.of(v1, v2));

System.out.println(merge.getNamedGraphs());
System.out.println("Consistent: " + VersionConsistencyChecker.isConsistent(graph));

// Write all the versions inside a single specific file
VersionGraphWriter.writeToFile(graph, java.nio.file.Path.of("versions-export.txt"));

// Or write each version in a different file, inside a directory
VersionGraphWriter.writeEachVersionToDirectory(graph, java.nio.file.Path.of("export-dir"));

// Generate the PROV-O graph describing the version graph
ProvOWriter.writeToFile(graph, java.nio.file.Path.of("export-dir/provenance.ttl"));

// Or build the whole graph from a definition file instead of hard-coding it
VersionGraph loaded = VersionGraphReader.read(
        java.nio.file.Path.of("src/benchmark/versioning/version-graph.txt"), MergePolicy.UNION);

// Reload the version graph from its PROV-O description (Apache Jena) and
// verify its consistency against the policy declared in the provenance
ProvOReader.ProvenanceGraph reloaded = ProvOReader.read(java.nio.file.Path.of("export-dir"));
System.out.println(VersionConsistencyChecker.isConsistent(reloaded.versions(), reloaded.policy()));
```

See `Main.java` for a richer scenario: three diverging branches, a binary merge, a post-merge
transition, an octopus merge (all loaded from `version-graph.txt`, then exported, reloaded
from the generated `provenance.ttl` and checked for consistency), and a negative test showing
how a tampered merge is detected through the same PROV-O round-trip.
