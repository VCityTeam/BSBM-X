# Formalization to model the version graph, incorporating a single, global merge policy

This modeling relies on Directed Acyclic Graph (DAG) theory and set theory.

1. Graph Structure and Data Space

Let us first define the foundational elements of the system, independently of the chosen merge policy:

The data universe ($E$): The set of all elements, files, or data blocks that can exist in the system. The content of a version will always be a subset of $E$ (an element of the power set of $E$), denoted $S \in \mathcal{P}(E)$.

The Directed Acyclic Graph (DAG) ($G$): Defined as $G = (V, A)$ where:

$V$ is the set of versions (the nodes).

$A \subseteq V \times V$ is the set of directed edges, representing the temporal parent-child relationship.

The direct history (Predecessors): For any node $v \in V$, we denote $pre(v)$ as the set of its direct parents:


$$pre(v) = \{u \in V \mid (u, v) \in A\}$$

2. The Global Policy Operator ($\oplus$)

Since we have a global policy, we define a universal operator $\oplus$ that will be systematically applied to every merge in the graph.

We choose the global policy from the three options:


$$\oplus \in \{\cup, \cap, \Delta\}$$

The major advantage of these three set operators is that they are all commutative and associative. This means that the order in which branches are merged does not matter, and they perfectly generalize to simultaneous merges of $n$ branches (often called "octopus merges" in Git).

For a merge node $v$ having $k$ parents $\{u_1, u_2, \dots, u_k\}$, the application of the global policy is written as:


$$\bigoplus_{u \in pre(v)} S(u) = S(u_1) \oplus S(u_2) \oplus \dots \oplus S(u_k)$$

3. System State Function

The actual state of each version is given by a function $S : V \to \mathcal{P}(E)$.
To formalize the construction of the graph from start to finish, the state $S(v)$ of any given version $v$ is determined according to three exhaustive cases, based on the in-degree (the number of parents) of the node:

Case 1: Root Node (Creation)
If there are no parents ($|pre(v)| = 0$), the node is an origin. Its state is an initial set (for example, the empty set, or a first file $X$).


$$S(v) = E_{initial}$$

Case 2: Transition Node (Linear Evolution)
If there is exactly one parent ($|pre(v)| = 1$, i.e., $pre(v) = \{u\}$), the node is a standard commit. The state is calculated by applying a differential of additions ($Additions_v$) and deletions ($Deletions_v$) to the parent's state.


$$S(v) = (S(u) \setminus Deletions_v) \cup Additions_v$$

Case 3: Merge Node (Policy Application)
If there are two or more parents ($|pre(v)| \geq 2$), the node is a merge. The final state is strictly the result of the global policy operator applied to the parents' states. No other modifications (additions or deletions) are allowed during this specific step.


$$S(v) = \bigoplus_{u \in pre(v)} S(u)$$

4. Graph Consistency Verification

The initial goal was to determine if a graph is "consistent". With this global formalization, the definition of consistency becomes binary and mathematically verifiable for any graph $G$.

A version graph $G = (V, A)$ equipped with a state function $S$ is said to be strictly consistent under the global policy $\oplus$ if and only if, for all merge nodes in the graph, the state perfectly matches the application of the operator:

$$\forall v \in V \text{ such that } |pre(v)| \geq 2, \quad S(v) = \bigoplus_{u \in pre(v)} S(u)$$

If even a single node in the graph deviates from this equality, it means that a parasitic element was introduced, or a legitimate element was arbitrarily deleted during the merge, thereby violating the system's global policy.