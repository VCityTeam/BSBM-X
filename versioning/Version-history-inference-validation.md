# Inference validation over the version history: rules, world assumptions and merge policies

This document extends the formalization of
[Generation-formalisation.md](Generation-formalisation.md) (sections 1–4: the DAG $G = (V, A)$,
the state function $S : V \to \mathcal{P}(E)$, the global policy operator
$\oplus \in \{\cup, \cap, \Delta\}$ and *strict consistency*). Section numbering continues from
there. The question addressed here is orthogonal to strict consistency:

> Given a set of rules $R$ (SHACL shapes, an RDFS or an OWL ontology), when is a version — and in
> particular a **merged** version — *valid*, and how does the choice of the global merge policy
> $\oplus$ create, propagate or repair invalidity?

Strict consistency (§4) constrains **how** a merge state was computed
($S(v) = \bigoplus_{u \in pre(v)} S(u)$, no tampering). Validity constrains **what the state
says**. The two are independent: a strictly consistent graph can be semantically invalid — indeed
the central result below is that the policy operator $\oplus$ itself manufactures invalidity even
when every parent is valid and the merge is computed exactly as prescribed.

The formalization is instantiated by the *Inference validation* program of this module
(`benchmark.versioning.InferenceValidationMain`, engine `InferenceValidator`); §11 maps the theory
onto the program and §12 gives worked micro-examples that are also executable as JUnit tests
(`InferenceValidationTest`).

## 5. Rules, entailment and validity

### 5.1. From quad sets to RDF graphs

In this module the data universe $E$ is a set of **quads** $(g, s, p, o)$. Rule languages (SHACL,
RDFS, OWL) are defined on **triples**, so validation operates on the projection

$$\pi(S) = \{(s, p, o) \mid (g, s, p, o) \in S\}$$

i.e. the union of all named graphs of the dataset. The projection interacts *asymmetrically* with
the three policy operators:

$$\pi(S_1 \cup S_2) = \pi(S_1) \cup \pi(S_2)
\qquad
\pi(S_1 \cap S_2) \subseteq \pi(S_1) \cap \pi(S_2)
\qquad
\pi(S_1 \,\Delta\, S_2) \supseteq \pi(S_1) \,\Delta\, \pi(S_2)$$

(the operators are overloaded: on the left of each relation they act on quad sets, on the right
on triple sets). Both inclusions can be strict when the *same triple* lives in *different named
graphs* in the two parents: the quads differ, so $\cap$ drops both (the triple silently disappears from the merge even
though every parent asserts it) and $\Delta$ keeps both. Validity under $\cap$ and $\Delta$ is
therefore sensitive to graph-placement divergence between branches, a purely quad-level phenomenon
invisible at the triple level. (§12, example B exploits exactly this.)

### 5.2. Two semantic regimes

A rule set $R$ is evaluated under one of two regimes:

**Entailment regime (RDFS, OWL — inference).** $R$ is an ontology; its semantics is a *closure
operator* $cl_R : \mathcal{P}(T) \to \mathcal{P}(T)$ over triple sets ($cl_R(X)$ = all triples
entailed by $X \cup R$). For RDFS-style rule sets, $cl_R$ is:

- *extensive*: $X \subseteq cl_R(X)$,
- *monotone*: $X \subseteq Y \Rightarrow cl_R(X) \subseteq cl_R(Y)$,
- *idempotent*: $cl_R(cl_R(X)) = cl_R(X)$.

In this regime the natural validity notion is **logical consistency**:

$$\mathrm{Cons}_R(S) \iff \pi(S) \cup R \text{ has a model.}$$

**Constraint regime (SHACL — validation).** $R$ is a set of shapes; its semantics is a
*conformance predicate* evaluated against the explicit graph:

$$\mathrm{Conf}_R(S) \iff \pi(S) \models_{\mathrm{SHACL}} R.$$

Conformance is in general **neither monotone nor antitone** in $S$: adding triples can both fix a
violation (supply a missing witness) and create one (exceed a maximum cardinality).

The **validity predicate** of a version is then

$$\mathrm{Valid}_R(v) \;=\; \begin{cases}
\mathrm{Conf}_R(S(v)) & \text{constraint regime (SHACL)}\\[2pt]
\mathrm{Cons}_R(S(v)) & \text{entailment regime (RDFS, OWL)}
\end{cases}$$

## 6. World assumptions and what each one can detect

### 6.1. Definitions

- **Closed World Assumption (CWA)** — a statement absent from $\pi(S(v))$ is treated as *false*.
  This is the reading of SHACL (and ShEx, and integrity constraints in databases): cardinalities,
  required values and closedness are checked against exactly what the graph asserts.
- **Open World Assumption (OWA)** — a statement absent from the graph is *unknown*. This is the
  reading of RDFS and OWL: an ontology never complains about *missing* information; it only
  derives new statements, and (for OWL) may derive a *contradiction*.

### 6.2. The two damage types of a merge

A merge $v$ with parents $u_1, \dots, u_k$ can damage the data in exactly two ways relative to its
parents:

- **Loss** — a statement required by $R$ (a witness: a type declaration, a mandatory property
  value, the target of a reference) is present in some/all parents but absent from
  $S(v)$. Characteristic of $\cap$ and $\Delta$.
- **Conflict** — statements that are individually acceptable but *jointly* violate $R$ (two values
  of a functional property, membership in two disjoint classes) are co-present in $S(v)$ although
  no single parent contains both. Characteristic of $\cup$ and $\Delta$.

### 6.3. Detectability matrix

| Damage | CWA (SHACL) | OWA (RDFS) | OWA (OWL) |
|---|---|---|---|
| **Loss** (missing witness, dangling reference, lost mandatory value) | **detected** (`sh:minCount`, `sh:hasValue`, `sh:class`, …) | *invisible* (absence is unknown, and inference may even re-derive the lost types) | *invisible* |
| **Conflict** (co-present incompatible statements) | **detected** (`sh:maxCount`, `sh:datatype`, `sh:closed`, …) | *almost invisible* (the only RDFS inconsistencies are datatype clashes under D-entailment, e.g. a literal outside the value space of a declared `rdfs:range`) | **detected** when $R$ contains negative axioms (`owl:disjointWith`, cardinality restrictions, `owl:differentFrom`, irreflexivity, …) |

Two consequences shape everything that follows:

1. **CWA and OWA are complementary detectors.** Closed-world validation is the only regime that
   sees loss-type damage ($\cap$, $\Delta$); open-world consistency checking sees conflict-type
   damage ($\cup$, $\Delta$) *provided the ontology states negative axioms*. An ontology made only
   of positive axioms (subclassing, domains, ranges) detects nothing at all: every merge of every
   policy is vacuously "valid" under it.
2. **OWL without a Unique Name Assumption can corrupt silently.** If $R$ declares
   `bsbm:price` functional and two branches give the same offer two different prices from IRIs (or
   two resources), the union merge is *not* inconsistent: OWL infers `owl:sameAs` between the two
   values and silently identifies them. The conflict only becomes a detectable inconsistency if
   the values are provably distinct (distinct literals, or `owl:differentFrom`). Closed-world
   validation of merges is therefore advisable *even when the vocabulary is OWL*.

## 7. A constraint taxonomy driven by merge behavior

To predict how a constraint behaves under $\oplus$ it is useful to write rules and constraints in
a common *guarded implication* form over the triple set $X = \pi(S)$:

$$\forall \bar{x}\; \big( \underbrace{a_1(\bar{x}) \wedge \dots \wedge a_n(\bar{x})}_{\text{guard } G} \;\rightarrow\; H(\bar{x}) \big)$$

where the $a_i$ are triple atoms and the head $H$ is either **positive-existential**
($\exists \bar{y}\, b_1 \wedge \dots \wedge b_m$ — a *witness requirement*) or **negative**
(a disequality, a non-membership, an upper bound on a count). SHACL constraints, RDFS rules and
(the rule-expressible fragment of) OWL axioms all take this form; e.g.:

- `sh:minCount 1` on path $p$ for target class $C$: guard $x \,\mathrm{a}\, C$, head
  $\exists y\, p(x,y)$ — **witness constraint** $\Phi^{+}$;
- `sh:class C` on path $p$ with `sh:targetSubjectsOf` $p$: guard $p(x,y)$, head
  $y \,\mathrm{a}\, C$ — witness constraint whose witness is *deterministic* (the type triple of
  the specific node $y$);
- `sh:maxCount 1` / `owl:FunctionalProperty`: guard $p(x,y_1) \wedge p(x,y_2)$, head $y_1 = y_2$ —
  **upper-bound constraint** $\Phi^{-}$;
- `owl:disjointWith`: guard $x \,\mathrm{a}\, C \wedge x \,\mathrm{a}\, D$, head $\bot$ —
  upper-bound constraint;
- `sh:datatype`, `sh:pattern`, `sh:nodeKind`: guard $p(x,y)$, head an *intrinsic* test on $y$
  (no other triple involved) — **intrinsic constraints**, a degenerate subclass of $\Phi^{-}$;
- an RDFS rule (`rdfs:subClassOf` transitivity, domain/range typing) is the same shape with a
  positive head, read as *inference* instead of *constraint*.

Identify a constraint — or a family of constraints checked together — with its **conformance
predicate** $\Phi : \mathcal{P}(E) \to \{\top, \bot\}$, where $\Phi(S)$ reads "$\pi(S)$ satisfies
the constraint(s)". Define **$\oplus$-safety** of $\Phi$ (for the $k$-ary operator):

$$\Phi \text{ is } \oplus\text{-safe} \iff \Big(\;\forall i\; \Phi(S(u_i))\;\Big) \Rightarrow \Phi\Big(\bigoplus_i S(u_i)\Big)$$

i.e. a merge of individually valid parents cannot violate $\Phi$. Three structural mechanisms
decide safety.

### 7.1. Straddling instantiations (break $\cup$)

In $S_1 \cup S_2$, a guard $G$ with $n \ge 2$ atoms can be instantiated with atoms taken from
*different parents* — an instantiation that exists in **no** parent and was therefore never
checked. All $\cup$-emergent violations are of this form:

- $p(x, y_1)$ from branch 1 and $p(x, y_2)$ from branch 2 instantiate the guard of
  `sh:maxCount 1` → two prices for one offer;
- $x \,\mathrm{a}\, C$ from branch 1 and $x \,\mathrm{a}\, D$ from branch 2 instantiate the guard
  of `owl:disjointWith`;
- even a *witness* constraint breaks if its guard has $\ge 2$ atoms: with target
  `sh:targetClass C` and constraint `sh:class D` on path $p$, the target triple
  $x \,\mathrm{a}\, C$ can come from branch 1 and the value triple $p(x,y)$ from branch 2, and
  neither branch ever checked $y$.

Conversely, a constraint whose guard is a **single atom** and whose head is positive-existential
is $\cup$-safe: every guard instantiation lives entirely in one (valid) parent, that parent
contains the required witness, and $\cup$ never removes it. By monotonicity the same argument
makes every *rule* fire at least as much: for inference,

$$cl_R(X_1) \cup cl_R(X_2) \;\subseteq\; cl_R(X_1 \cup X_2)$$

with strictness precisely when a rule guard straddles the branches (branch 1 asserts
$C \sqsubseteq D$, branch 2 asserts $D \sqsubseteq F$: only the merge entails
$C \sqsubseteq F$). Under $\cup$, straddling produces **emergent violations** for constraints and
**emergent entailments** for rules — the same mechanism, read under CWA and OWA respectively.

### 7.2. Witness divergence (breaks $\cap$)

In $S_1 \cap S_2$, a guard that both parents agree on survives, but the *witness* satisfying an
existential head may be a **different** triple in each parent — in which case no witness survives:

- both branches keep `offer1 a bsbm:Offer` but branch 1 satisfies `sh:minCount 1` on
  `bsbm:price` with value `"11.0"` and branch 2 with `"12.0"`: the intersection contains the
  offer and no price;
- at the quad level (§5.1), the agreement itself can be illusory: the same witness *triple* stored
  in different *named graphs* is two different quads, and $\cap$ drops it.

A witness constraint is $\cap$-safe only when the witness is **deterministic** — uniquely
determined by the guard, like the type triple of the specific node referenced by `sh:class` —
since a deterministic witness present in both (valid) parents is present in the intersection
(modulo the graph-placement caveat of §5.1). Upper-bound constraints, on the other hand, are
trivially $\cap$-safe by antitonicity: $S_1 \cap S_2 \subseteq S_1$, and a violation present in a
subset is present in the superset (contrapositive: a valid parent has no violating instantiation,
and $\cap$ adds nothing). The same subset argument yields the central OWA result:

$$\mathrm{Cons}_R(S_1) \Rightarrow \mathrm{Cons}_R(S') \text{ for every } S' \subseteq S_1
\qquad\Longrightarrow\qquad \text{consistency is } \cap\text{-safe.}$$

For inference the direction reverses relative to §7.1:

$$cl_R(X_1 \cap X_2) \;\subseteq\; cl_R(X_1) \cap cl_R(X_2)$$

strictly, when both parents entail the same conclusion *from different premises*: agreement on
conclusions is not agreement on support, and the intersection loses entailments that **every**
parent had. $\cap$ under OWA silently shrinks the knowledge, and OWA (by §6) cannot flag it.

### 7.3. Core cancellation (breaks $\Delta$ twice over)

For $k = 2$, $S_1 \,\Delta\, S_2 = (S_1 \cup S_2) \setminus (S_1 \cap S_2)$: the symmetric
difference **removes exactly the common core** — which is where the stable knowledge lives (schema
triples, long-lived type declarations, everything inherited from the merge base) — and **retains
exactly the divergent increments** of both branches. It therefore triggers *both* previous
mechanisms simultaneously:

- like $\cap$-damage, but worse: not just divergent witnesses but every *agreed* witness is
  deleted (a review added in branch 1 survives; the product it references, present in both
  branches, is deleted);
- like $\cup$-damage: the two sides of a straddling conflict are each in exactly one parent, so
  both survive (`x a Product` from branch 1 and `x a Review` from branch 2 are both kept — the
  disjointness violation of §7.1 is *not* cancelled).

Two further $\Delta$-specific phenomena:

- **Vacuous validity.** $S \,\Delta\, S = \emptyset$, and more generally $\Delta$ tends toward
  small states; under CWA an empty or near-empty graph often conforms *vacuously* (no targets →
  no violations). A $\Delta$-merge can thus be reported valid because nothing checkable is left.
  Validity verdicts on $\Delta$-merges should always be read together with the dataset size.
- **Parity sensitivity ($k \ge 3$).** The $k$-ary symmetric difference keeps the elements present
  in an **odd** number of parents, so in an octopus merge a triple shared by *all three* parents
  survives while a triple shared by exactly two is deleted. "Removes the agreement" is only the
  $k = 2$ reading; safety analyses must be done at the arity actually used.

### 7.4. Safety theorems, summarized

**Lemma (locality).** The only constraints safe under all three policies (at every arity, for
arbitrary valid parents) are those decidable *triple by triple* — the intrinsic family. Safety:
a violating instantiation of an intrinsic constraint is a single triple; every policy result is a
subset of the union of the parents' quads ($\cap \subseteq S_1$, $\Delta \subseteq \cup$), so
every merged triple originates in some valid parent, which contained no violating triple.
Necessity: every constraint that *relates* two triples is breakable by some policy — §7.1–§7.3
supply the counterexamples.

| Constraint / semantic family | example | $\cup$ | $\cap$ | $\Delta$ |
|---|---|---|---|---|
| Intrinsic (single-atom guard, per-triple head) | `sh:datatype`, `sh:pattern` | **safe** | **safe** | **safe** |
| Witness, single-atom guard, deterministic witness | `sh:class` + `sh:targetSubjectsOf` | **safe** | **safe**¹ | broken (core cancellation) |
| Witness, non-deterministic witness | `sh:minCount`, `sh:hasValue` via `sh:targetClass` | **safe**² | broken (witness divergence) | broken |
| Upper-bound / negative (multi-atom guard) | `sh:maxCount`, `owl:FunctionalProperty`, `owl:disjointWith`, `sh:closed` | broken (straddling) | **safe** (antitone) | broken (conflict retention) |
| OWA consistency $\mathrm{Cons}_R$ | OWL with negative axioms | broken | **safe** | broken |
| Entailment set $cl_R$ | RDFS/OWL inference | grows (emergent entailments) | shrinks (support loss) | incomparable |

¹ modulo the named-graph projection caveat (§5.1). ² provided the *target* guard is a single atom;
multi-atom target+path combinations straddle (§7.1).

Reading the columns: **$\cup$ manufactures conflicts, $\cap$ manufactures losses, $\Delta$
manufactures both** — and each policy is also the *repair operator* for the damage type of the
opposite column ($\cap$ repairs conflicts by dropping one side; $\cup$ repairs losses by
re-supplying witnesses; $\Delta$ repairs precisely the violations wholly contained in the common
core, since it deletes that core).

## 8. Validity of merges: outcome taxonomy

For a merge node $v$ with $pre(v) = \{u_1, \dots, u_k\}$, combining the parents' validity with the
merge's validity yields four exhaustive outcomes:

$$\mathrm{outcome}(v) = \begin{cases}
\textsf{PRESERVED} & \forall i\, \mathrm{Valid}_R(u_i) \;\wedge\; \mathrm{Valid}_R(v)\\
\textsf{EMERGENT\_VIOLATION} & \forall i\, \mathrm{Valid}_R(u_i) \;\wedge\; \neg\mathrm{Valid}_R(v)\\
\textsf{REPAIRED} & \exists i\, \neg\mathrm{Valid}_R(u_i) \;\wedge\; \mathrm{Valid}_R(v)\\
\textsf{INHERITED\_VIOLATION} & \exists i\, \neg\mathrm{Valid}_R(u_i) \;\wedge\; \neg\mathrm{Valid}_R(v)
\end{cases}$$

The outcome assigns **responsibility**:

- `EMERGENT_VIOLATION` is attributable to the *policy operator itself* — under strict consistency
  (§4) nobody edited the merge, so the violation was manufactured by $\oplus$. In the PROV-O
  export of this module the merge activity is `prov:wasAssociatedWith` the policy agent
  (`agt:policy-<POLICY>`), which is exactly the right provenance target for the blame.
- `INHERITED_VIOLATION` is attributable to the branch(es) that were already invalid; the merge
  merely propagated it (though it may also have *added* emergent damage on top — distinguishing
  the two requires diffing the violation sets, not just the verdicts).
- `REPAIRED` is the constructive reading of §7's damage table: $\cap$ repairs a conflict present
  in one branch only, $\cup$ repairs a missing witness, $\Delta$ repairs any violation entirely
  supported inside the common core.
- `PRESERVED` is what a $\oplus$-safe constraint class guarantees *a priori* (§7.4); for unsafe
  classes it must be re-checked.

Characteristic emergent modes per policy (the anti-diagonal of §7.4):

| Policy | characteristic emergent violation | characteristic repair |
|---|---|---|
| $\cup$ | conflict/accumulation: `sh:maxCount`, functional properties, `owl:disjointWith`, divergent datatypes | supplies missing witnesses (`sh:minCount`, dangling references) |
| $\cap$ | amputation/loss: `sh:minCount`, `sh:hasValue`, divergent witnesses, named-graph divergence | drops one side of a conflict (`sh:maxCount`, disjointness); **always preserves OWA consistency** |
| $\Delta$ | both at once: core cancellation (dangling references to deleted core) + conflict retention | deletes violations shared by an even number of parents; beware vacuous validity |

## 9. Validity of the whole history

Lift the predicate to the graph (with $M = \{v \in V : |pre(v)| \ge 2\}$ the merge nodes):

- **History validity**: $\mathrm{Valid}_R(G) \iff \forall v \in V,\ \mathrm{Valid}_R(S(v))$.
- **Merge-relative validity**: $\forall v \in M,\ \mathrm{Valid}_R(S(v))$ — appropriate when
  transitions are validated at authoring time (like CI on commits) and only the *computed* states
  need auditing.
- **Metrics** (reported by the program): validity rate $|\{v : \mathrm{Valid}_R(v)\}| / |V|$, and
  the outcome histogram over $M$ (preserved / emergent / repaired / inherited). The emergent rate
  is a property *of the policy* under the workload, and is the number to compare across
  $\cup, \cap, \Delta$ when benchmarking policies.

**Orthogonality with strict consistency (§4).** All four combinations are realizable: a strictly
consistent graph with emergent violations (the policy computed exactly what it had to, and that
result is invalid); a tampered graph that happens to be valid (the parasitic quad conforms); etc.
Strict consistency audits the *process*, inference validation audits the *content*; a version
history benchmark needs both checks.

**Incremental validation corollary.** By §7.4, after a merge whose parents are all valid, only the
non-$\oplus$-safe constraint families can have new violations:

| after a merge under | it suffices to re-check |
|---|---|
| $\cup$ | upper-bound/negative constraints (intrinsic ones excepted) and OWA consistency — the conflict detectors |
| $\cap$ | witness constraints — the loss detectors, including the deterministic-witness ones *only* because of the named-graph caveat of §5.1; OWA consistency needs **no** re-check |
| $\Delta$ | everything **except intrinsic constraints** — the one family that is $\Delta$-safe (locality lemma, §7.4) |

Intrinsic constraints never need re-checking at any merge of valid parents, whatever the policy:
by the locality lemma their violations are single triples, and every merged triple comes from a
valid parent. Roots and transitions carry authored changes and are validated in full, like
ordinary commits.
This gives a sound cost model for validating long histories: the constraint set is partitioned
once (by guard arity, head polarity and witness determinism, §7), and each merge re-checks only
the classes its policy endangers.

## 10. Choosing rules and assumptions for a versioned dataset

Practical consequences of §6–§9 when *designing* the rule set $R$ of a versioned RDF dataset:

1. **Pair every OWA ontology with a CWA profile.** RDFS/OWL alone cannot see the loss damage that
   $\cap$ and $\Delta$ merges produce. The natural pattern is one vocabulary, two readings:
   OWL axioms for inference and consistency, plus SHACL shapes (possibly mechanically derived
   from the cardinality/domain/range axioms) for closed-world merge auditing.
2. **State negative axioms.** Under OWA, only negative axioms (`owl:disjointWith`, cardinality
   restrictions, `owl:differentFrom`) give the reasoner anything to refute; a purely positive
   ontology validates every merge vacuously (§6.3).
3. **Mind the non-UNA identification trap** for functional/cardinality axioms on IRI-valued
   properties: prefer SHACL `sh:maxCount` when "two different names ⇒ two different things" is the
   intended reading (§6.3, item 2).
4. **Interpret $\Delta$-verdicts with sizes.** A conforming $\Delta$-merge with a near-empty
   dataset is usually vacuous validity (§7.3), i.e. a false reassurance.
5. **Validate at merge nodes even in fully-audited pipelines**: merges are the only nodes whose
   state nobody authored; they are exactly where `EMERGENT_VIOLATION` lives, and under strict
   consistency their invalidity is a property of the chosen policy, not of any contributor.

## 11. Instantiation: the Inference validation program

The module implements §5–§9 as follows.

| Formal object | Implementation |
|---|---|
| rule set $R$ | a Turtle/RDF file: SHACL shapes or an RDFS/OWL ontology (`RuleLanguage` auto-detected from the namespaces used, or forced with `--language`) |
| regime & world assumption | `SHACL` → constraint regime, CWA (Jena SHACL engine); `RDFS` → entailment regime, OWA (Jena RDFS reasoner + D-entailment clashes); `OWL` → entailment regime, OWA (Jena OWL rule reasoner: disjointness, functional-property and datatype clashes) |
| $\pi(S(v))$ | the union of the named graphs of the version's quad set (§5.1) |
| $\mathrm{Valid}_R(v)$ | `InferenceValidator.validate(version)` → verdict + violation/inconsistency reports |
| outcome taxonomy (§8) | `InferenceValidator.validateHistory(versions)` → per-version verdicts + per-merge `MergeOutcome` (`PRESERVED`, `EMERGENT_VIOLATION`, `REPAIRED`, `INHERITED_VIOLATION`) |
| history metrics (§9) | the report's summary: validity rate and outcome histogram |

The program validates a version history previously exported by `Main` (the `provenance.ttl`
PROV-O description plus one N-Quads file per version, reloaded with `ProvOReader`):

```bash
# Validate every exported policy sub-directory against the example SHACL shapes
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain -Dexec.args="--rules src/main/resources/rules/shacl-shapes.ttl --dir versions-export"

# Open-world consistency check of one directory against the OWL ontology
mvn compile exec:java -Dexec.mainClass=benchmark.versioning.InferenceValidationMain -Dexec.args="--rules src/main/resources/rules/owl-ontology.ttl --language owl --dir versions-export/union"
```

Exit codes: `0` — every version valid; `1` — at least one violation (suitable for CI); `2` —
usage error. `Main` also accepts `--rules <file>` (and `--rule-language`) to run the validation
inline after generating and reloading each policy's export.

Three example rule files over the BSBM-flavored vocabulary of the generator live in
`src/main/resources/rules/`, together covering every constraint family of §7:

- `shacl-shapes.ttl` (CWA): deterministic-witness constraints (§7.2) — every `bsbm:reviewFor`,
  `bsbm:product` and `bsbm:vendor` value must be a declared entity of the right class —,
  non-deterministic witness constraints via `sh:targetClass` (§7.1–§7.2) — a declared product
  keeps its label, a declared offer keeps its price and its product link —, upper bounds (§7.1)
  — at most one price/rating/country per subject — and intrinsic constraints (§7.4) — datatypes,
  patterns, the 1..10 rating scale. Against generated histories this detects the dangling
  references and lost witnesses that $\cap$ and $\Delta$ merges (and deleting transitions)
  produce.
- `rdfs-ontology.ttl` (OWA): classes (with a small `rdfs:subClassOf` hierarchy), domains, ranges
  and a `rdfs:subPropertyOf` — deliberately all-positive, to exhibit §6.3's blindness: every
  version of every policy validates.
- `owl-ontology.ttl` (OWA): the same vocabulary with negative axioms (pairwise class
  disjointness, functional `bsbm:price`, `bsbm:rating1`, `bsbm:deliveryDays` on literals — plus
  functional `bsbm:product`, `bsbm:vendor`, `bsbm:producer` on IRIs, which illustrate the
  non-UNA trap of §6.3 — and an `owl:inverseOf`), giving the OWA regime something to refute.

**Executable metagraph counterpart.** The policy-level part of this formalization is also
encoded as executable Jena forward rules over the PROV-O export
(`src/main/resources/rules/metagraph.rules`): the $\oplus$-safety matrix of §7.4 (as
`mg:safeFor`/`mg:endangers` axioms), the containment and ∩-safety results of §7.2 (as
`mg:datasetSubsetOf` facts with antitone/monotone `mg:owaConsistent` propagation), the outcome
taxonomy of §8 (as `mg:outcome` rules over externally asserted `mg:valid` verdicts, with
`mg:responsibleFor` assigning the blame of §8) and the incremental re-validation plan of §9 (as
`mg:mustRecheck`). A Jena `GenericRuleReasoner` derives all of it directly from
`provenance.ttl` — see README §8. The Inference validation program runs these rules with
`--metagraph-rules <file>`: it asserts every version's $\mathrm{Valid}_R$ verdict as an
`mg:valid` fact on the PROV-O export, cross-checks the rule-derived `mg:outcome` of every merge
against the engine's §8 classification, and exports the derived statements as
`metagraph-<shacl|rdfs|owl>-infered.ttl`.

## 12. Worked micro-examples

Each example is executable: `InferenceValidationTest` builds exactly these graphs. Quads are
written as triples here (named graph omitted) except in example B.

**A. $\cup$ manufactures a conflict (straddling, §7.1).**
Shapes: `bsbm:Offer` targets must bear `bsbm:price` with `sh:minCount 1; sh:maxCount 1`.
Root $S(V_0) = \{$`offer1 a Offer`, `offer1 price "10.0"`$\}$; branch $V_1$ replaces the price
with `"11.0"`, branch $V_2$ with `"12.0"`. Both branches conform.
$\cup$-merge: $\{$`offer1 a Offer`, `price "11.0"`, `price "12.0"`$\}$ — `sh:maxCount` violated →
`EMERGENT_VIOLATION`.

**B. $\cap$ manufactures a loss (witness divergence, §7.2).**
Same shapes, same graph. $\cap$-merge: the two branches agree only on `offer1 a Offer`; each
price exists in one branch → the merge is an offer with **no** price — `sh:minCount` violated →
`EMERGENT_VIOLATION`. (Variant, quad level: both branches keep the *triple*
`product1 a Product` but branch 1 stores it in a different *named graph*; the quads differ, the
intersection loses the type triple, and every reference to `product1` dangles — §5.1.)

**C. $\Delta$ cancels the core (§7.3).**
Shapes: every `bsbm:reviewFor` value must be a declared `bsbm:Product` (`sh:class`).
Root $\{$`product1 a Product`$\}$; branch $V_1$ adds `review1 reviewFor product1`; branch $V_2$
adds an unrelated offer. Both branches conform, and so would the $\cup$- and $\cap$-merges.
$\Delta$-merge: `product1 a Product` is in *both* parents → deleted; `review1 reviewFor product1`
is in one parent → kept. The reference dangles → `EMERGENT_VIOLATION`.

**D. Repair and inheritance are policy-relative (§8).**
Root $\{$`offer1 a Offer`, `price "10.0"`$\}$; branch $V_1$ *adds* `price "11.0"` (now invalid:
two prices); branch $V_2$ adds an unrelated product (valid).
$\cap$-merge = root state → conforms → `REPAIRED`. $\cup$-merge keeps both prices →
`INHERITED_VIOLATION`. Same parents, same rules — the verdict is a property of $\oplus$.

**E. OWA conflict detection, and the $\cap$-safety of consistency (§7.2–7.3).**
Ontology: `bsbm:Product owl:disjointWith bsbm:Review`. Empty root; branch $V_1$ adds
`x a Product`; branch $V_2$ adds `x a Review`. Each branch is consistent.
$\cup$-merge: inconsistent → `EMERGENT_VIOLATION`. $\Delta$-merge: both type triples are
single-parent, so both survive → inconsistent too. $\cap$-merge: empty → consistent →
`PRESERVED`, as guaranteed by the theorem of §7.2.

**F. OWA is blind to loss (§6.3).**
Run example B's graph against `rdfs-ontology.ttl` (domains/ranges, all positive): every version
of every policy, including the amputated $\cap$-merge, is reported valid. The same graph under
the SHACL shapes is invalid at the merge — the two regimes disagree exactly as the detectability
matrix predicts.
