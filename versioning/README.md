# Versioning — RDF Version Graph with a Global Merge Policy

This module (`benchmark.versioning`) implements the mathematical formalization of a
**version graph** modeled as a Directed Acyclic Graph (DAG), where:

- every version holds an **RDF dataset** made of **quads** (subject, predicate, object, graph name),
- the graph supports **multiple branches** and **n-ary merges** ("octopus" merges),
- all merges are governed by a single **global merge policy** `⊕ ∈ {∪, ∩, Δ}`,
- graph **consistency** can be verified: every merge node must satisfy
  `S(v) = ⊕ S(u), u ∈ pre(v)`,
- version and merge **validity** can be checked against a rule set (SHACL shapes, an RDFS or an
  OWL ontology) by the **Inference validation** program — see §7 and the companion research
  document [Version-history-inference-validation.md](Version-history-inference-validation.md).

**All quad/triple parsing and serialization is done with [Apache Jena](https://jena.apache.org/).**
A quad is a Jena [`org.apache.jena.sparql.core.Quad`](https://jena.apache.org/documentation/javadoc/arq/org/apache/jena/sparql/core/Quad.html)
(`(graph, subject, predicate, object)` of Jena `Node`s); per-version datasets are written and read
as **N-Quads**, and the PROV-O description of the version graph is built and written as **Turtle** —
both by Jena.

---

## 1. Project layout

This is a **self-contained Maven module** at the root of the repository, with the standard layout:

```
versioning/
├── pom.xml
├── README.md
├── Generation-formalisation.md                  # formal model of the version graph (§1–§4)
├── Version-history-inference-validation.md      # formal model of inference validation (§5–§12)
├── src/main/java/benchmark/versioning/          # the programs
├── src/main/resources/rules/                    # example rule sets (SHACL, RDFS, OWL)
└── src/test/java/benchmark/versioning/          # the JUnit 5 tests
```

The rest of the BSBM sources keep the legacy Ant build (`../build.xml`) and its jars under `../lib/`;
this module is independent of them.

---

## 2. Building and running

Prerequisites: **JDK 17+** and **Maven**. The only dependencies (**Apache Jena** for all RDF
parsing/serialization, JUnit 5 for the tests) are fetched by Maven. Every command below runs
from this directory:

```bash
cd versioning     # if you are at the repository root
mvn compile       # build the module
mvn test          # run the JUnit 5 tests
```

### 2.1 Quick start: generate the versions, then create the inferred versions

**Step 1 — Generate and export the version histories.** Run the demo program
(`benchmark.versioning.Main`, the default main class of `exec:java`):

```bash
mvn compile exec:java
```

This generates the version graph from the default parameters (§2.2) and exports it once per
merge policy: `versions-export/union/`, `versions-export/intersection/` and
`versions-export/symmetric-difference/`, each containing **one N-Quads file per version**
(`V0.nq` … `V9.nq`, `M1.nq`, `M2.nq`) plus the **PROV-O description** of the version graph
(`provenance.ttl`). Every policy section of the output ends with:

```
Reloaded from provenance.ttl: 12 versions (1 root, 9 transitions, 2 merges)
Reloaded graph is consistent: true
```

**Step 2 — Create the inferred versions.** Run the **Inference validation** program
(`benchmark.versioning.InferenceValidationMain`) with an **RDFS or OWL** rule set (the *rule
type*). For every version file `<id>.nq` of each validated history, it materializes the
knowledge entailed by that version into a new file `<id>-<ruletype>-infered.nq` next to it:

```bash
# RDFS rule type -> creates <id>-rdfs-infered.nq next to every <id>.nq
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain \
    -Dexec.args="--rules src/main/resources/rules/rdfs-ontology.ttl --dir versions-export"

# OWL rule type -> creates <id>-owl-infered.nq
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain \
    -Dexec.args="--rules src/main/resources/rules/owl-ontology.ttl --dir versions-export"

# Only test one merge policy: --policy restricts the run to the history whose
# provenance.ttl declares that policy (union | intersection | symmetric-difference)
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain \
    -Dexec.args="--rules src/main/resources/rules/rdfs-ontology.ttl --dir versions-export --policy union"
```

Besides the per-version verdicts and the merge classification (§7), each validated directory
reports the created files:

```
=== versions-export/union (policy UNION) ===
  ...
  Wrote 12 inferred-knowledge files (*-rdfs-infered.nq) to versions-export/union
  Summary: 12/12 versions valid; merges: 2 preserved, 0 emergent-violation, 0 repaired, 0 inherited-violation
```

**Step 3 — Inspect an inferred version.** Each file is valid N-Quads: a `#` comment header,
then one line per inferred statement, all placed in the dedicated named graph
`http://example.org/graph/inferred`. `versions-export/union/V0-rdfs-infered.nq` (excerpt):

```
# ===== Inferred knowledge export (N-Quads) =====
# version: V0
# rule language: RDFS (open-world regime)
# asserted quads: 20
# inferred statements: 33 (named graph http://example.org/graph/inferred)
<http://example.org/offer1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/Offer> <http://example.org/graph/inferred> .
<http://example.org/review2> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/Review> <http://example.org/graph/inferred> .
...
```

No version ever *asserts* `ex:offer1 rdf:type bsbm:Offer`: this statement is **entailed** by the
`rdfs:domain` of `bsbm:price` declared in the ontology. What exactly goes into these files — and
why a SHACL rule set produces none (it validates but entails nothing) — is specified in §7,
“Inferred-knowledge files”. Re-running the program simply overwrites them.

### 2.2 Generation parameters

The graph is **not hard-coded and not read from a file**: it is generated from the
program parameters (see §5.9):

| Option | Meaning | Default |
|---|---|---|
| `--versions <n>` | Total number of versions in the graph (root + transitions + merges). | 12 |
| `--branches <n>` | Number of branches. | 3 |
| `--merges <n>` | Number of merge nodes. | 2 |
| `--initial-quads <n>` | Number of quads in the initial dataset of the root. | 20 |
| `--evolution <n>` | Number of quads changed between two versions (deletions + additions applied by each transition). | 6 |
| `--seed <n>` | Random seed, for reproducible graphs. | 42 |
| `--export-dir <dir>` | Export directory. | `versions-export` |
| `--rules <file>` | Also run the **Inference validation** (§7) of each exported history against these rules (SHACL shapes or an RDFS/OWL ontology) and print its summary. | (none) |
| `--rule-language <l>` | `shacl` \| `rdfs` \| `owl` \| `auto` — how to read the rules file. | `auto` (detected) |

Constraints: `versions >= branches + merges`, and `branches >= 2` when `merges > 0`.

```bash
# Example: generate a larger custom graph (then create its inferred versions as in §2.1, step 2)
mvn compile exec:java -Dexec.args="--versions 40 --branches 5 --merges 6 --initial-quads 100 --evolution 12 --seed 7"
```

For each policy (`UNION`, `INTERSECTION`, `SYMMETRIC_DIFFERENCE`) the demo:

1. generates the graph from the parameters (the same seed produces the same DAG for
   every policy — only the datasets downstream of the merges differ),
2. **exports each version in a different N-Quads file** and generates the **PROV-O graph
   describing the version graph** (`provenance.ttl`),
3. **reloads the whole version graph from the generated `provenance.ttl`** (parsed with
   Apache Jena — see §5.10): the DAG structure and the policy come from the PROV-O
   description, the dataset S(v) of each version from its exported N-Quads file,
4. prints a summary and `Reloaded graph is consistent: true`.

The **consistency assertions** (lossless round-trip, consistency of every policy, and detection
of a tampered merge) are exercised by the JUnit tests in `src/test/java` — see §3.

The export directory (`versions-export/` by default, or `--export-dir`) contains one
sub-directory per demo policy (file names shown for the default parameters):

```
versions-export/
├── union/
│   ├── V0.nq  V1.nq  ...  V9.nq  M1.nq  M2.nq   # one N-Quads file per version
│   ├── V0-rdfs-infered.nq  V0-owl-infered.nq ... # inferred knowledge per version (written by
│   │                                             # the Inference validation program, §7)
│   └── provenance.ttl                            # PROV-O description of the DAG (Turtle)
├── intersection/            (same layout)
└── symmetric-difference/    (same layout)
```

---

## 3. Tests

The tests live in `src/test/java/benchmark/versioning/` and run with `mvn test`:

| Test | Role |
|---|---|
| `PolicyRoundTripConsistencyTest` | Parameterized over the three policies: generate → export → reload from `provenance.ttl` (Jena) → assert the round-trip is lossless and the reloaded graph is consistent under the declared policy. (Formerly `Main.testPolicy`.) |
| `InconsistencyDetectionTest` | Builds a merge tampered with a parasitic quad (violating the global UNION policy), exports it, reloads it and asserts it is detected as inconsistent through the PROV-O round-trip. (Formerly `Main.testInconsistencyDetection`.) |
| `InferenceValidationTest` | Executable version of the worked micro-examples of [Version-history-inference-validation.md](Version-history-inference-validation.md) §12: each merge policy creating (`EMERGENT_VIOLATION`), propagating (`INHERITED_VIOLATION`) or repairing (`REPAIRED`) invalidity under SHACL (closed world) and RDFS/OWL (open world) rules, plus rule-language detection and the example rule files run against generated histories. |
| `InferredKnowledgeTest` | The **inferred knowledge** of a version and its `<id>-<rdfs\|owl>-infered.nq` export files (§7): RDFS domain/range typing, OWL `owl:sameAs` from a functional property, how `∪` accumulates and `∩` loses the branches' inferences, that SHACL entails nothing, and that the inferred files parse as N-Quads without disturbing the PROV-O round-trip. |
| `InferenceValidationMainTest` | The Inference validation **program**: the `--policy` parameter (selects the history by the merge policy read from `provenance.ttl`), the inferred-knowledge files it materializes (RDFS/OWL yes, SHACL no), and the exit codes (including `2` on usage errors and unmatched policies). |

---

## 4. Classes overview

| Class | Role |
|---|---|
| `Vocabulary` | Central definition of the RDF vocabulary (the `ex:`, `bsbm:`, `rdf:` namespaces and the three named graphs), and factory helpers building Jena `Node`s and `Quad`s. The single place where domain terms become Jena nodes. |
| `Version` | A node `v ∈ V` of the DAG: an id, an immutable RDF dataset `S(v)` (a `Set<Quad>` of Jena quads), and the list of its parents `pre(v)`. |
| `MergePolicy` | The global operator `⊕`: `UNION`, `INTERSECTION` or `SYMMETRIC_DIFFERENCE`. Commutative and associative, hence n-ary merges are well defined. |
| `VersionGraph` | Builds the DAG: root nodes, transition nodes (diff of additions/deletions) and merge nodes (strict application of `⊕`). |
| `VersionConsistencyChecker` | Verifies the strict consistency of a graph (or of an arbitrary collection of versions) under a policy. |
| `VersionGraphWriter` | Writes the versions of a graph to disk with Jena: **one N-Quads file per version**, or a single human-readable report of all versions. |
| `VersionGraphGenerator` | **Generates a version graph from parameters** (versions, branches, merges, initial dataset size, evolution per version, seed) under a given policy. |
| `ProvOWriter` | Generates, with Jena, an **RDF graph describing the version graph using the W3C PROV-O ontology** (Turtle). |
| `ProvOReader` | **Reads a version graph back from its PROV-O description** (`provenance.ttl`, parsed with Apache Jena) and the per-version N-Quads files. |
| `Main` | Runnable demonstration program (generate → export → reload → summarize, optionally validate with `--rules`). |
| `RuleLanguage` | The rule languages of the Inference validation (`SHACL`, `RDFS`, `OWL`), each tied to its world assumption (closed/open), with namespace-based auto-detection. |
| `InferenceValidator` | The **Inference validation engine**: validates every version against a rule set, classifies every merge by the outcome taxonomy (`PRESERVED`, `EMERGENT_VIOLATION`, `REPAIRED`, `INHERITED_VIOLATION`) and, under RDFS/OWL, materializes the **inferred knowledge** of each version (`inferredKnowledge`, `writeInferredFiles`). |
| `InferenceValidationMain` | Runnable **Inference validation** program: reloads exported histories (optionally selected with `--policy`), prints the per-version verdicts, the merge classification and the summary, and writes the `<id>-<rdfs\|owl>-infered.nq` files (§7). |

### About quads and named graphs

Every quad is an Apache Jena `Quad` — `(graph, subject, predicate, object)` of Jena `Node`s. Build
them through `Vocabulary`, which expands the `ex:`, `bsbm:` and `rdf:` prefixes to full IRIs:

```java
import org.apache.jena.sparql.core.Quad;

Quad q = Vocabulary.quad(
        Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),   // named graph
        Vocabulary.ex("product1"),                    // ex:product1
        Vocabulary.rdfType(),                         // rdf:type
        Vocabulary.bsbm("Product"));                  // bsbm:Product
```

The graph node of a `Quad` is an IRI identifying the **named graph** the triple belongs to inside
the RDF dataset. It is **deliberately independent of the version identifiers**: several versions may
contain quads of the same named graph, and one version may span several named graphs. Use
`Version.getNamedGraphs()` to view a version's dataset grouped by graph (`Map<Node, Set<Quad>>`).

---

## 5. Usage guide

### 5.1. Choose the global policy and create the graph

The policy is chosen **once**, globally, when the graph is created. It is applied to every
merge in the graph.

```java
VersionGraph graph = new VersionGraph(MergePolicy.UNION);
// or MergePolicy.INTERSECTION, MergePolicy.SYMMETRIC_DIFFERENCE
```

### 5.2. Create a root node (Case 1: |pre(v)| = 0)

A root is an origin of the history. Its state is the initial RDF dataset (possibly empty).

```java
Set<Quad> initialData = Set.of(
        Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product")),
        Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("price"), Vocabulary.literal("42.0")));

Version v0 = graph.createRoot("V0", initialData);
```

### 5.3. Create transition nodes (Case 2: |pre(v)| = 1) — branching

A transition is a standard commit: `S(v) = (S(u) \ Deletions) ∪ Additions`.
**Several transitions may share the same parent — this is how branches are created.**

```java
// Branch 1: add a review
Version v1 = graph.createTransition("V1", v0,
        Set.of(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("review1"), Vocabulary.bsbm("reviewFor"), Vocabulary.ex("product1"))),
        Set.of());

// Branch 2: also starts from v0
Version v2 = graph.createTransition("V2", v0,
        Set.of(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product2"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"))),
        Set.of());
```

### 5.4. Create merge nodes (Case 3: |pre(v)| ≥ 2)

The state of a merge is **strictly** the result of the global policy applied to the parents'
datasets — no additions or deletions are allowed during a merge. Any number of parents ≥ 2 is
supported (octopus merge).

```java
Version m1 = graph.createMerge("M1", List.of(v1, v2));              // binary merge
Version m2 = graph.createMerge("M2", List.of(v1, v2, v0));          // octopus merge
```

`createMerge` throws `IllegalArgumentException` if fewer than 2 parents are given.
All creation methods throw `IllegalArgumentException` if the version id already exists.

### 5.5. Verify graph consistency

A graph is **strictly consistent** iff every merge node's state equals the policy applied to
its parents' states:

```java
boolean ok = VersionConsistencyChecker.isConsistent(graph);

// Or check an arbitrary collection of versions against a policy (e.g. loaded from storage)
boolean ok2 = VersionConsistencyChecker.isConsistent(versionsCollection, MergePolicy.UNION);
```

When a violation is found, the checker prints a `[DEBUG_LOG]` message with the offending
version id, the expected dataset and the actual dataset, and returns `false`.

### 5.6. Inspect a version

```java
version.getId();          // the version identifier
version.getData();        // Set<Quad> — the full RDF dataset S(v) (immutable, Jena quads)
version.getParents();     // List<Version> — pre(v)
version.getNamedGraphs(); // Map<Node, Set<Quad>> — dataset grouped by named graph
```

### 5.7. Write each version in a different N-Quads file

`VersionGraphWriter.writeEachVersionToDirectory` writes **one file per version** inside a
directory. Each file is named after the version id (sanitized), with the `.nq` extension:
`V0.nq`, `M1.nq`, …

```java
// One file per version, inside the directory (created if missing).
// Returns the list of files written, in topological order.
List<Path> files = VersionGraphWriter.writeEachVersionToDirectory(graph, Path.of("export-dir"));

// Also works with an arbitrary collection of versions and a policy
VersionGraphWriter.writeEachVersionToDirectory(versionsCollection, MergePolicy.UNION, Path.of("export-dir"));

// Or serialize a single version without touching the file system
String text = VersionGraphWriter.serializeVersion(version, MergePolicy.UNION);
```

Each file starts with a short `#` comment header (skipped by the Jena N-Quads parser on read)
followed by the full RDF dataset `S(v)` as sorted N-Quads lines. Example of `M1.nq`:

```
# ===== RDF version export (N-Quads) =====
# version: M1
# global merge policy: UNION
# kind: merge (2 parents)
# parents: V1, V2
# quads: 2
<http://example.org/offer1> <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/price> "42.0" <http://example.org/graph/offers> .
<http://example.org/product1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/Product> <http://example.org/graph/products> .
```

`VersionGraphWriter.writeToFile` / `serialize` produce, instead, a single human-readable report of
all the versions of a graph (kind, parents and dataset per section), also using Jena to format the
quad lines. If you export several graphs that reuse the same version ids (like the three demo
policies), write each graph into its **own sub-directory** to avoid file-name clashes.

### 5.8. Describe the version graph with PROV-O

`ProvOWriter` builds, with Jena, an RDF graph (Turtle) that **describes the version graph itself**
using the [W3C PROV-O ontology](https://www.w3.org/TR/prov-o/):

```java
ProvOWriter.writeToFile(graph, Path.of("export-dir/provenance.ttl"));
String ttl = ProvOWriter.serialize(graph);   // or just get the Turtle text
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
ver:M1  a                    prov:Entity ;
        rdfs:label           "Version M1" ;
        rdfs:comment         "merge node with 5 quad(s)" ;
        prov:wasDerivedFrom  ver:V1 , ver:V2 ;
        prov:wasGeneratedBy  act:merge-M1 .

act:merge-M1  a                  prov:Activity ;
        rdfs:label               "Merge (2 parents, policy UNION) producing M1" ;
        prov:used                ver:V1 , ver:V2 ;
        prov:generated           ver:M1 ;
        prov:wasAssociatedWith   agt:policy-UNION .
```

### 5.9. Generate a version graph from parameters

`VersionGraphGenerator` builds a `VersionGraph` from a handful of **parameters**, so the
graph is neither hard-coded in Java nor read from a file:

```java
// versions, branches, merges, initialQuads, evolutionQuads, seed
VersionGraphGenerator.Parameters params =
        new VersionGraphGenerator.Parameters(12, 3, 2, 20, 6, 42);

VersionGraph graph = VersionGraphGenerator.generate(params, MergePolicy.UNION);
```

Connection rules of the generated DAG:

- the graph starts with a single root `V0` holding the initial dataset (`initialQuads` fresh quads);
- each of the `branches - 1` additional branches is opened by a **transition forking from the head
  of a randomly chosen existing branch**;
- the remaining transitions **advance the head of a randomly chosen branch**;
- the merges are **evenly interleaved** among those transitions; each merge combines the heads of
  2 — or sometimes 3, when at least 3 branches exist (**octopus**) — randomly chosen distinct
  branches, and becomes the new head of the first of them; the other merged branches keep their
  heads and stay active, as in git;
- every transition applies the evolution differential to its parent's dataset: it **deletes
  `evolutionQuads / 2` random quads** (capped by the dataset size) and **adds the remaining
  `evolutionQuads - evolutionQuads / 2` fresh quads**. Fresh quads are BSBM-flavored (products,
  offers, reviews) Jena quads that rotate over the three named graphs.

Transitions are named `V1..Vn` and merges `M1..Mm`, in creation order. **The state of merge nodes
is never generated**: it is computed by applying the global policy to the parents' datasets.

The generation is **deterministic for a given seed**, and the DAG structure does not depend on the
policy: two independent random streams are used, one for the structure (fork/branch/merge choices)
and one for the data (deletion picks). The same parameters replayed under different policies
therefore produce the same DAG — only the datasets downstream of the merges differ.

### 5.10. Read the version graph back from its PROV-O description

`ProvOReader` (based on **Apache Jena**) reconstructs a version graph from an export directory: the
generated `provenance.ttl` is the **authoritative description of the version graph**, and the
dataset S(v) of each version is loaded (with Jena) from its `<id>.nq` N-Quads file next to it.

```java
// Reload the version graph described by <dir>/provenance.ttl; the dataset of
// each version is read from its <id>.nq file in the same directory.
ProvOReader.ProvenanceGraph loaded = ProvOReader.read(Path.of("versions-export/union"));

List<Version> versions = loaded.versions();   // parents-first order
MergePolicy policy     = loaded.policy();     // declared by the policy agent

boolean ok = VersionConsistencyChecker.isConsistent(versions, policy);
```

`ProvOReader.read` fails with an `IOException` if the provenance file, the policy agent or a version
dataset file is missing, if a version derives from an entity not described in the file, or if the
`prov:wasDerivedFrom` graph contains a cycle. Note that `prov:wasDerivedFrom` statements have set
semantics, so a merge listing the same parent twice cannot be represented in (or read back from)
PROV-O.

---

## 6. Merge policy semantics

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

## 7. Inference validation (rules, world assumptions, merge validity)

The **Inference validation** program checks the validity of **all versions** of an exported
history against a rule set, classifies every **merge**, and (under RDFS/OWL) creates the
per-version **inferred-knowledge files** — a step-by-step walkthrough from a fresh checkout is
in §2.1. Its formal foundations — what "valid" means per rule language and world assumption,
and which merge policy endangers which constraint family — are developed in
[Version-history-inference-validation.md](Version-history-inference-validation.md), which extends
the formalization of [Generation-formalisation.md](Generation-formalisation.md).

```bash
# 1. Generate and export the three policy histories (once)
mvn compile exec:java

# 2. Validate every exported policy sub-directory against the example SHACL shapes
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain -Dexec.args="--rules src/main/resources/rules/shacl-shapes.ttl --dir versions-export"

# Or check open-world consistency of one directory against the OWL ontology
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain -Dexec.args="--rules src/main/resources/rules/owl-ontology.ttl --language owl --dir versions-export/union"

# Or test a single merge policy: only the history whose provenance.ttl declares
# the UNION policy agent is validated (and gets its *-rdfs-infered.nq files)
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain -Dexec.args="--rules src/main/resources/rules/rdfs-ontology.ttl --dir versions-export --policy union"
```

| Option | Meaning | Default |
|---|---|---|
| `--rules <file>` | The rule set: SHACL shapes or an RDFS/OWL ontology (Turtle/RDF). **Required.** | — |
| `--language <l>` | `shacl` \| `rdfs` \| `owl` \| `auto`. | `auto` (detected from the namespaces) |
| `--dir <dir>` | Directory to validate: either it contains `provenance.ttl` + `<id>.nq` files, or each of its sub-directories does (the per-policy layout written by `Main`). | `versions-export` |
| `--policy <p>` | The **merge policy to test**: `union` \| `intersection` \| `symmetric-difference` (case-insensitive, `-` or `_`). Only the histories whose global merge policy — read from `provenance.ttl`, the authoritative description — is `<p>` are validated; matching none is a usage error. | validate every history found |

Exit code: `0` — every version valid, `1` — at least one violation (CI-friendly), `2` — usage
error.

### Inferred-knowledge files (`<id>-<rdfs|owl>-infered.nq`)

Under the RDFS/OWL **entailment regimes**, the program also materializes the **inferred
knowledge** of every version: next to each version file `<id>.nq` it writes
`<id>-rdfs-infered.nq` (resp. `<id>-owl-infered.nq`) containing every statement entailed by the
version's triple projection together with the ontology — the deductive closure of the
schema-bound Jena reasoner — that is **neither asserted in the version nor already entailed by
the ontology alone**. Under RDFS this is what domains, ranges and class hierarchies add (e.g.
`ex:review2 rdf:type bsbm:Review` from the domain of `bsbm:reviewFor`); under OWL also what the
negative and equality axioms add (`owl:differentFrom` pairs from the disjointness axioms,
`owl:sameAs` from a functional property, `owl:Thing` memberships, …).

The files are valid N-Quads (a `#` comment header, then sorted lines, parsable with Jena); the
inferred statements are placed in the dedicated named graph
`http://example.org/graph/inferred` (`Vocabulary.GRAPH_INFERRED`), so they can never be confused
with asserted quads, and their presence does not disturb the PROV-O reload (`ProvOReader` only
resolves `<id>.nq` files). Under SHACL nothing is written: the constraint regime **validates but
entails nothing** (`InferenceValidator.supportsInference()` is `false`).

The rule language decides the **regime and world assumption**:

| Language | Regime | World assumption | Detects |
|---|---|---|---|
| `SHACL` | constraint validation (Jena SHACL engine) | **closed world** | loss damage (missing witnesses, dangling references — typical of `∩`/`Δ` merges) *and* conflict damage (`sh:maxCount`, …) |
| `RDFS` | consistency of the inference model (Jena RDFS reasoner) | **open world** | only datatype clashes; blind to loss |
| `OWL` | consistency of the inference model (Jena OWL reasoner) | **open world** | conflict damage via negative axioms (`owl:disjointWith`, functional properties — typical of `∪`/`Δ` merges); blind to loss |

Every merge node is classified by crossing the parents' validity with the merge's validity:

| Outcome | Parents | Merge | Meaning |
|---|---|---|---|
| `PRESERVED` | all valid | valid | nothing to report |
| `EMERGENT_VIOLATION` | all valid | invalid | the violation was **manufactured by the merge policy itself** |
| `REPAIRED` | some invalid | valid | the policy dropped the offending statements |
| `INHERITED_VIOLATION` | some invalid | invalid | the violation was propagated from a branch |

Three example rule files over the generator's BSBM-flavored vocabulary live in
`src/main/resources/rules/`: `shacl-shapes.ttl` (referential integrity of reviews + price upper
bounds; with the default generation parameters the deleting transitions and the `∩`/`Δ` merges
produce dangling references that it flags), `rdfs-ontology.ttl` (deliberately all-positive:
validates everything, exhibiting open-world blindness to loss) and `owl-ontology.ttl` (adds
disjointness and a functional price, giving the open-world regime something to refute).

Programmatic use (see `InferenceValidator`):

```java
InferenceValidator validator = InferenceValidator.fromFile(Path.of("rules.ttl")); // auto-detects
InferenceValidator.VersionValidity verdict = validator.validate(version);
InferenceValidator.HistoryReport report = validator.validateHistory(loaded.versions());
report.merges().forEach(m -> System.out.println(m.mergeId() + " -> " + m.outcome()));
System.out.println(report.summary());

// RDFS/OWL only: materialize the inferred knowledge of every version
if (validator.supportsInference()) {
    Set<Quad> inferred = validator.inferredKnowledge(version);          // in Vocabulary.GRAPH_INFERRED
    validator.writeInferredFiles(loaded.versions(), exportDirectory);   // <id>-<rdfs|owl>-infered.nq
}
```

---

## 8. Complete example

```java
import benchmark.versioning.*;
import org.apache.jena.sparql.core.Quad;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

VersionGraph graph = new VersionGraph(MergePolicy.UNION);

Version v0 = graph.createRoot("V0",
        Set.of(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_PRODUCTS),
                Vocabulary.ex("product1"), Vocabulary.rdfType(), Vocabulary.bsbm("Product"))));

Version v1 = graph.createTransition("V1", v0,
        Set.of(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_OFFERS),
                Vocabulary.ex("offer1"), Vocabulary.bsbm("price"), Vocabulary.literal("42.0"))),
        Set.of());

Version v2 = graph.createTransition("V2", v0,
        Set.of(Vocabulary.quad(Vocabulary.iri(Vocabulary.GRAPH_REVIEWS),
                Vocabulary.ex("review1"), Vocabulary.bsbm("reviewFor"), Vocabulary.ex("product1"))),
        Set.of());

Version merge = graph.createMerge("M", List.of(v1, v2));

System.out.println(merge.getNamedGraphs());
System.out.println("Consistent: " + VersionConsistencyChecker.isConsistent(graph));

// Write each version in a different N-Quads file, plus the PROV-O description
VersionGraphWriter.writeEachVersionToDirectory(graph, Path.of("export-dir"));
ProvOWriter.writeToFile(graph, Path.of("export-dir/provenance.ttl"));

// Reload the version graph from its PROV-O description (Apache Jena) and verify consistency
ProvOReader.ProvenanceGraph reloaded = ProvOReader.read(Path.of("export-dir"));
System.out.println(VersionConsistencyChecker.isConsistent(reloaded.versions(), reloaded.policy()));
```

See `Main.java` for the full demonstration (several diverging branches, binary and octopus merges
generated from the program parameters, exported, reloaded from the generated `provenance.ttl`), and
the tests in `src/test/java` for the lossless round-trip, consistency and tampered-merge checks.
