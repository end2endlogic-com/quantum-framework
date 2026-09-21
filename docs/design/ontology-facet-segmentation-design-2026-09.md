# Ontology Facet Segmentation

Status: design proposal
Date: 2026-09-17
Scope: Lattas ontology (TBox), Shield policy evaluation, pack admission, Quantum tenancy boundary

Related:

- `docs/lattas-shield-design-implementation-guide-2026-08.html` — §03 invariants,
  §06 the binding record, §11 the receipt, §19 realm model, workstream 5, open decision 2
- `docs/companion-smolvm-execution-isolation-design-2026-09.md` — the same
  authority-split pattern applied to execution
- `framework/docs/design/ontology-relationship-filtering-and-metadata-spec.md` — policy-driven
  relationship filtering grammar, enforcement mechanics, and edge metadata extensions
- `helixor-employee/docs/QUANTUM_DURABLE_STATE.md` — the current realm/DataDomain
  tenancy model and its explicit "no raw pymongo" rule
- `helixor-entity-governance/README.md` — existing precedent: deterministic engine
  logic in a module, tenant persistence left in `helixorq-tenant`

---

## Origin

Two questions, asked in sequence:

1. How do we layer Quantum's data segmentation concepts into the Lattice ontology
   without requiring the full Quantum + MongoDB stack?
2. If entities and attributes can carry arbitrary metadata, and policies can filter
   on it, does that generalize the problem away?

The answer to (2) is *almost* yes, and this document exists because the "almost" is
load-bearing. Unrestricted key/value metadata dissolves the governed policy
vocabulary, which is one of the three properties §02 of the guide identifies as
structurally hard to copy. The design below keeps the flexibility and keeps the guard.

---

## 1. The reframe: three things Quantum conflates

Quantum's segmentation is one concept doing three jobs. Separating them is most of
the work, and the separation is what removes the MongoDB requirement.

| Concern | What it is | Where it should live |
|---|---|---|
| **Semantics** | The coordinate (`realm · org · account · tenant · dataSegment`), the hierarchy, and the comparison rules | Admitted ontology (TBox) + a store-free decision library |
| **Carriage** | How a given row acquires its coordinate | Declared per binding; three modes (§6) |
| **Mechanics** | Realm→database routing, Morphia annotations, the filter-string DSL, CDI wiring | Stays in Quantum, unchanged |

The segmentation model is **a coordinate system and a comparison function**. It is
not a storage layout. It currently looks like a storage layout because
`MorphiaRepo.getListByQuery(realm, …)` makes the realm a mandatory first argument and
`FullBaseModel` is where `bmFunctionalArea` / `bmFunctionalDomain` happen to be stamped.

---

## 2. The action space is not the ontology

**Functional Area / Functional Domain / Functional Action describe an action that can be
taken.** Paired with an attribute-based filter, they define the scope that action may
affect from a data perspective.

This is deliberately separate from ontology entities and must stay separate. Actions are
coarse-grained and routinely span many entities; entities are fine-grained and singular.

### The worked example

*"View orders for location ABC"* is one grant. Servicing it requires reading Customer,
Shipment, Invoice, Inventory and several relationship types. The person granting it does
not think in those terms — they think **"access to orders."**

Requiring the grant to enumerate the transitive entity closure would be:

- unusable for the person granting it;
- brittle, because it breaks the moment the implementation adds a join;
- and wrong in the other direction too, since the same entity participates in many
  actions at different scopes.

So: **the grant names the action, the filter names the slice, and the ontology is what
makes the slice mean the same thing across every entity the action happens to traverse.**

### Where the two axes meet

A row carries a coarse functional coordinate — which is what
`FullBaseModel.bmFunctionalArea()` / `bmFunctionalDomain()` already do. That coordinate
is the **rendezvous** between grant and data: it is how a grant finds data at all. It is
a classification for matching, **not an ontology identity**. The mapping to entities is
loose on purpose and must stay loose.

```
grant  = (functionalArea, functionalDomain, functionalAction)   <- capability: what may be done
       x scopeFilter over facets                                <- data scope: over which slice

data   coarse functional coordinate   -> match key for grants
       facet values                   -> scope evaluation
       ontology binding               -> meaning
```

**Facets do not replace the functional triple.** Facets are the vocabulary the *scope
filter* is written in; the functional triple is the vocabulary the *capability* is written
in. Both are admitted; neither subsumes the other.

### 2.1 Three consequences

**Grant coarse, attest fine.** Because the grant never enumerated the entities, the
receipt must. Under coarse grants the lineage chain stops being a nicety and becomes the
only place the actual reach of an action is visible. The user grants "view orders"; the
receipt reports the four entities and three edge types actually traversed.

**Pushdown validation moves from per-binding to per-action.** A facet declared
`pushdown: required` must be satisfiable by *every binding the action can reach* — not
only the binding it was declared against. If one reachable source cannot push the
predicate, it is the **action** that is unsafe and must be refused, not just that binding.

**Reachability preview is transparency, not safety.** A coarse grant can pull an edge
nobody considered — the `Person --memberOf--> WorksCouncil` case from the white paper §7
is reached by an ordinary-looking HR action. Edge-level enforcement (§3) is what makes
that *safe*; the preview is what makes it *reviewable*. Granting this action reaches these
entities, these relationship types and these data classes — shown before the grant is
made, so an over-broad grant is caught by a person rather than merely contained by the
engine.

---

## 3. Enforcement is at the edge

Permissions are enforced **as the ontology is traversed**, not only at the point a query
is admitted.

A principal permitted to view Customer reaches Inventory, Invoice, Order, Organization and
Location through Customer's relationships. Each of those is governed by **its own
entity-specific filter**, and each relationship by its own edge rule.

> **The historical exception this resolves.** The hard case was never "filter a
> collection." It was **pulling a graph** — a connected result spanning many collections,
> where each collection has its own policy. That is the case a per-tool or per-collection
> filter cannot express at all.

### 3.1 Resolved once, applied per entity

This is not in tension with "policy is evaluated once, on the ontology query" — it is the
other half of it. But the thing resolved is a **map, not a predicate**. It is emphatically
not one filter re-applied at every hop.

```
resolve  ONCE   -> a filter MAP, pinned to one principal and one policy revision
                     { entity    -> row filter + field exclusions }
                     { edgeType  -> traversal rule }

apply    PER    -> entering entity E applies E's filter
         HOP        crossing relationship R applies R's rule
```

Single resolution against a pinned policy revision is what stops two implementations of
one rule diverging. **Per-entity application is what lets one grant mean something
different — and correct — at Customer, at Invoice and at Inventory.**

### 3.2 Two lookups, two keys

They are separate mechanisms and must not be collapsed:

| | Keyed by | Answers |
|---|---|---|
| **Entity filter** | ontology entity | which rows of this entity are visible, and which fields are stripped |
| **Edge rule** | relationship type | may this relationship be traversed at all |

An edge rule that denies stops expansion before the far entity's filter is ever consulted.
An entity filter that empties does not imply the edge was denied — and the receipt must
distinguish the two, because "you may not traverse this" and "you traversed it and nothing
qualified" are different disclosures.

**Entity filters are path-independent by decision.** An entity's filter does not vary with
the route taken to reach it; edge rules gate the route. The requirement that looks
path-dependent — *"invoices for customers you can see"* — falls out of traversal itself,
because those are the only invoices reachable from a visible Customer, with the Invoice
filter then applied on top. Admitting genuinely path-dependent entity filters would
multiply the rule space by the path space; it is refused.

### 3.3 Pulling a graph is a staged plan

The traversal compiles into a **multi-stage plan with one stage per hop, each stage
carrying its own entity's filter** — not a query per collection stitched together
afterwards, and not one query post-filtered.

```
stage 1  Customer    match(customer filter)
stage 2  --placedBy--> Order     edge rule ok -> match(order filter)
stage 3  --billedOn--> Invoice   edge rule ok -> match(invoice filter)
stage 4  --shipsFrom-> Location  edge rule DENIED -> prune, do not read
```

Quantum already has a concrete realization of this: an **aggregation pipeline spanning
collections**, where policy compiles into the per-stage match rather than running after
the lookup. The general form is store-agnostic — a SQL join tree with predicates pushed
to each relation, a per-call filter on an API source, a pre-filter on a vector source —
and Mongo's pipeline is one instance of it, not the definition.

This is what makes the pushdown claim in §3.6 real: the filter enters the plan at the
stage that reads the data, so rows the principal cannot see are never materialized into
the join.

### 3.4 When the filters get resolved

Resolving a filter for every reachable entity up front requires knowing the reachable set,
which §2.1 only computes for transparency. Two options, and the second is recommended:

- **Eager** — compute the reachable set, resolve every entity filter before execution.
  Predictable and inspectable, but pays for entities the traversal never visits.
- **Lazy, against a pinned revision** — pin the policy revision and principal at the start,
  then resolve and memoize each entity's filter on first entry. Preserves "resolved once"
  semantically, because every resolution is against the same pinned revision, while
  avoiding work for unvisited branches.

The pin is the load-bearing part. Lazy resolution against a *live* policy set would let a
mid-traversal policy change produce a result no single revision would have produced.

### 3.5 What this settles

**Coarse grants do not need their reach enumerated in order to be safe.** Every hop is
filtered regardless, so an edge nobody anticipated is governed on arrival rather than at
design time. This supersedes the safety framing in §2.1: reachability preview remains
valuable, but as *transparency*, not as the thing that makes the grant sound.

**Edge marking stops being a special case.** If the unit of enforcement is the traversal,
a sensitive relationship — `Person --memberOf--> WorksCouncil` — is governed by the
natural mechanism rather than an exception bolted onto node scoring. A denied edge halts
expansion, so the far side is never fetched and cannot leak through an aggregate, a count,
or a timing difference.

**A traversal is a derivation.** The exclusion-floor rule already defined for derived
attributes (§7) applies unchanged along a path: restriction accumulates and cannot be
widened by a later hop.

### 3.6 Four rules the traversal needs

| Rule | Why |
|---|---|
| **An unknown edge type is not traversable** | A relationship no admitted rule mentions must be refused, not followed. Otherwise every ontology extension silently widens every standing grant. This is the edge analogue of `onAbsent: deny`. |
| **Restriction is monotonic along a path** | Once a hop trims, no downstream hop may restore. Deny wins; the exclusion floor is a floor. |
| **Re-entry by another path unions, and records which path** | A node reached by an allowed path is visible even when another path to it was denied. Lineage must record the path actually used, and the caller-facing copy must not let the denied path be inferred from what is absent. |
| **Traversal carries a declared budget** | Coarse grant plus deep graph is unbounded expansion. Depth and fan-out bounds are declared on the action; exceeding them is a typed refusal, never a silent truncation. |

### 3.7 Consequences for pushdown and the receipt

**Pushdown is what keeps this affordable — and correct.** Each entity's filter enters the
plan at the stage that reads that entity (§3.3), so a denied edge prunes before the far
side is read and a restricted entity never contributes invisible rows to the join. That is
the difference between *"we did not return it"* and *"we never read it"* — a distinction
that carries the whole containment argument, so per-stage pushdown is a correctness
requirement rather than an optimisation.

**The trim record must name withheld edges, not only rows and fields.** The receipt's
lineage already carries relations with predicate, asserted/derived and support count. The
trim record needs the symmetric entry: *this edge type was not traversed, by this rule.*

---

## 4. Facets: open set, closed validation

Rather than hard-coding the four Quantum axes as typed fields, segmentation is
expressed as **facets** — named, declared pieces of metadata attachable to ontology
entities and attributes, filterable from policy.

The facet **set is open**. A customer or a vertical pack declares `jurisdiction`,
`caseTeam`, `contractId`, `retentionClass` — whatever the domain needs.

The facet **declarations are admitted**. They enter the TBox, are versioned and
hash-pinned, and a policy naming an unadmitted facet is refused at registration with
the offending literal named — exactly as predicates are today.

This is the existing registry-over-hard-coded axiom applied to tenancy.

### 4.1 Why unrestricted key/value is not sufficient

Five failure modes, each of which the declaration closes. These are recorded because
they are the justification for the extra ceremony, and the ceremony will look like
overhead to anyone who has not hit them.

| Failure | What goes wrong with bare metadata | Closed by |
|---|---|---|
| **Undeclared operator** | A record carries `segment: 3`. Is the rule `==`, `<=`, or set membership? Each policy author decides, and two decide differently. Silent under-redaction. | `operator` |
| **Absence permits** | A record with no `classification` matches no rule; "no rule matched" resolves to allow. This is the canonical label-selector leak. | `onAbsent` |
| **Typos are invisible policy** | `dataSegment` vs `data_segment` — a rule on the misspelling matches nothing and reports as active. Reintroduces precisely the failure the vocabulary guard prevents. | admission |
| **Composition has no direction** | High-water classification and weakest-link confidence need to know which facets are ordinal and how they order. Derived artifacts inherit nothing; the exclusion floor stops working. | `kind`, `propagates` |
| **Pushdown unknowable** | A facet not physically present in the source cannot be pushed into the query, so rows are fetched then filtered — meaning data crossed the boundary before being dropped. A containment failure, not a perf note. | `pushdown` |

---

## 5. Facet declaration contract

```
facet  dataSegment
  kind        ordinal            # nominal | ordinal | set | hierarchical
  domain      0..9               # declared value space
  operator    record <= principal
  onAbsent    deny               # deny | inherit | allow (allow requires steward sign-off)
  propagates  high-water         # none | high-water | union | weakest-link
  pushdown    required           # required | optional | none
  sensitive   false              # may this facet's carrier be read to resolve a coordinate?
```

Admission rules:

1. A facet whose `kind` and `operator` disagree is refused (an `ordinal` facet cannot
   declare set membership).
2. `onAbsent: allow` requires an explicit steward declaration and is recorded on
   every receipt that relies on it. The default is `deny`.
3. `pushdown: required` is validated at **binding** registration, not at query time:
   a binding that cannot push the facet into its source is refused.
4. `sensitive: true` facets may not be used as coordinate carriers (see §9.1).
5. Facet declarations live in the same admitted TBox as the access rules. No second
   vocabulary — see invariant "No dual ontology" in the guide.

---

## 6. Carriage: the move that removes the MongoDB requirement

Each bound source declares **how** its rows acquire facet values. Only one of the
three modes needs anything stamped on a document.

```
coordinateCarriage:
  stamped   — the source physically carries the value (a Quantum document,
              a tenant_id column). Bound as an attribute like any other.

  constant  — the entire source IS one value (per-tenant schema, per-region
              bucket, a credential that only ever sees one tenant's data).
              Declared once on the binding. Zero per-row cost.

  derived   — computed from other bound attributes by an admitted rule
              (Supplier.registeredRegion = 'EU' -> realm eu-prod).
```

In practice most customer-owned sources are `constant` — they were bound with a
credential scoped to one tenant already — and the remainder are `derived`. Neither
requires a Quantum document, a Morphia entity, or a realm database.

**Quantum's `stamped` mode becomes one carriage option among three rather than the
only way to participate.**

---

## 7. Enforcement: realm is admission, facets are predicates

Keep this distinction sharp or the property that makes Quantum safe is lost in the
generalization.

- **Realm is not a facet.** It is an admission gate resolved *before* lowering. A
  realm mismatch means the query is never constructed — not that it runs and returns
  zero rows. A cross-realm join must be structurally unrepresentable, not
  policy-prevented. The moment realm becomes a filterable facet, it is a predicate
  someone can write a rule to relax, trading a structural guarantee for a policy one.

- **Everything else is a predicate** in the single filter applied at lowering, before
  the resolution mode is chosen. This preserves the existing invariant: static and
  dynamic paths inherit one filter.

### 7.1 Replacing the safety Quantum gave for free

`getListByQuery(realm, …)` makes it impossible to forget the realm — the compiler
asks for it. Removing that requires a type-level replacement:

> The lowered-query object **cannot be constructed** without a resolved coordinate.
> Non-optional constructor parameter, not a nullable field validated later.

---

## 8. Composition

**Entity and attribute both carry facets; the effective value is the stricter.**
Same logic as union-of-exclusions. An attribute may be more restricted than its
entity but never less — otherwise a permissive attribute facet becomes a laundering
path for a restricted entity.

**Derivations inherit per `propagates`.** A derived attribute takes the union of
field exclusions across every binding the derivation read, and for each facet applies
that facet's declared propagation rule. `high-water` is a **floor**, not always the
answer: aggregation can push sensitivity beyond any single input, so the hook for a
pack-supplied aggregation rule that raises it stays.

---

## 9. Traps

### 9.1 Derived coordinates are a covert channel

If `realm` is derived from a field, reading that field cannot itself be realm-gated —
that is circular. Bootstrap rule: **coordinate-carrying attributes resolve at a fixed
low clearance and must be declared non-sensitive**, refused at binding registration
otherwise. This is what `sensitive` on the facet declaration is for.

### 9.2 Ordinal and set axes do not compose

`dataSegment <= n` and compartment membership are different operators and both are
needed. Collapsing compartments into a numeric level is the usual shortcut and it
silently over-grants.

### 9.3 One vocabulary, or the dual-ontology problem

Segmentation terms must live in the same admitted TBox the access rules validate
against. If Quantum stamps from one vocabulary and Shield validates against another,
rules reference terms nothing produces.

---

## 10. Impact on Quantum

### 10.1 Two different decouplings — only one is wanted

| | Decouple segmentation *semantics* from Mongo | Make Quantum persistence database-agnostic |
|---|---|---|
| Effort | Low — falls out of §4–§6 | 6–12 months |
| Value | High — removes Quantum from the critical path for every Lattice engagement | Low — no customer is asking |
| Cost | None identified | Loses realm→DB routing (physical isolation), change streams (phase-two event backbone), and the schemaless fit for pack-driven payloads |
| Verdict | **Do** | **Do not** |

Nobody buys "runs on your database." They buy "governs your data where it already
is" — which is the binding story, not the persistence story. If a regulated customer
mandates Postgres-only, that is a per-deal dedicated deployment question, not a
product rewrite.

### 10.2 What moves out of Quantum

- Facet declarations → pack artifacts (files), not Mongo documents.
- The decision (principal + facets → filter + trim record) → a store-free library.
  This is the projection over `RuleContext` and the access-list resolver already
  scheduled as workstream 5.
- The hierarchy closure → a pack artifact. It must be hash-pinned anyway because the
  receipt records the TBox hash, so it loads into memory at admission.

### 10.3 What stays in Quantum, and should

Realm→database routing; durable domains; the system plane; provisioning and
decommission; `getListByQuery(realm, …)` as the persistence API; Morphia.

Quantum's role sharpens from **"the tenancy system"** to **"the high-assurance
deployment tier that adds physical isolation to semantics defined elsewhere."**

### 10.4 What runtime actually requires

Principal coordinate (verified token claims), binding coordinate (ontology manifest —
a file), hierarchy closure (pack artifact). The entire segmentation decision is pure
comparison with no store behind it.

### 10.5 The strategic point

Quantum is currently on the critical path for everything: a Lattice pilot requires a
Quantum deployment requires a Mongo. **That coupling is the cost, not the choice of
store.** It is also why the "compete on the second store" wedge does not currently
work — the second store requires first installing one of ours.

---

## 11. The extraction trap

If the decision library is extracted and Quantum **reimplements** the comparison in
Java rather than calling it, the result is the dual-implementation leak one level up
from the one Fig 5 of the white paper describes: two implementations of one rule, and
the day they disagree it is a data leak rather than a bug report.

This forces **open decision 2** sooner than planned — does the policy decision execute
as a hop to a service, or as a compiled rule projection evaluated locally?

> Recommendation: take the hop and measure it. A local projection introduces cache
> coherence *inside the enforcement path*, and a stale projection fails open in the
> worst possible way.

The structural countermeasure mirrors the MCP-surface rule in §01 of the guide:

> **If the decision library is only ever called by Quantum, the extraction did not
> happen.** It must be exercised by a non-Quantum caller early, even when nobody has
> asked for it, or the boundary is decoration.

---

## 12. Invariants

Each should be testable, and ideally have a test that fails when violated.

1. A policy naming an unadmitted facet is refused at registration, with the offending
   literal named.
2. A facet with no declared `onAbsent` defaults to `deny`; `allow` requires a recorded
   steward declaration.
3. A binding that cannot satisfy `pushdown: required` is refused at binding
   registration, not at query time — and an **action** whose reachable binding set
   contains any such binding is itself refused.
4. Realm is resolved before lowering and never appears as a filterable predicate.
5. A lowered query cannot be constructed without a resolved coordinate.
6. An attribute's effective facet value is never less strict than its entity's.
7. Coordinate-carrying attributes are declared non-sensitive and resolve at fixed low
   clearance.
8. Facet declarations and access rules validate against the same TBox hash.
9. Exactly one implementation of the facet comparison exists; every other tier calls it.
10. A grant is expressible without naming ontology entities; the receipt names every
    entity and edge type the action actually traversed.
11. The reachable entity and edge set of an action is computable and shown before the
    grant is made.
12. An edge type no admitted rule mentions is not traversable.
12a. The resolved artifact is a filter **map** keyed by entity and by relationship type,
    pinned to one principal and one policy revision — never a single predicate.
12b. Every entity filter in a traversal resolves against the same pinned policy revision,
    whether materialized eagerly or on first entry.
12c. Each entity's filter is injected at the plan stage that reads that entity, not
    applied to the assembled result.
13. Restriction along a traversal path is monotonic — no hop may widen what an earlier
    hop trimmed.
14. A denied edge prunes before the far side is read; "not returned" and "never read"
    are distinguishable on the receipt.
15. The trim record names withheld edge types, not only withheld rows and fields, and
    distinguishes "this relationship could not be traversed" from "it was traversed and
    nothing qualified."

---

## 13. Acceptance test

> Run a pilot against a customer's Postgres, their object storage, and one API — with
> **no MongoDB anywhere in the deployment** — and produce a receipt carrying a trim
> record naming the rule and predicate for every withheld row and stripped field.

If that runs, the decoupling is real. It is also exactly the second-store wedge.

---

## 14. Open questions

1. **Where does the decision execute?** Service hop vs compiled local projection
   (guide open decision 2). Recommendation above; needs measurement.
2. **One binding vocabulary or two?** Materialization mode and computed-edge providers
   already solve static/dynamic for edges on the Java side; the facet path needs the
   same semantics for attributes. Prior art may exist in the rule-index snapshot type —
   find how far it went before choosing.
3. **Is the functional coordinate on data single- or multi-valued?** A row may
   legitimately belong to more than one functional domain. Single-valued keeps matching
   trivial but leaves some data unreachable by actions that should reach it;
   multi-valued makes grant matching a set intersection and complicates the trim record.
4. **Is an action's reachable entity set declared or computed?** Declared is auditable
   and stable but drifts as the implementation adds joins. Computed at admission is
   accurate but moves underneath a standing grant when the ontology changes. Edge
   enforcement (§3) lowers the stakes considerably — a drifting set can no longer widen
   access, only widen the *preview* — so this is now a transparency question rather than
   a containment one. Recommendation stands: compute it, pin it into the grant at
   admission, re-validate on ontology version change rather than resolving live.
5. **Facet cardinality limits.** An open set invites sprawl. Is there a per-pack
   ceiling, and is exceeding it a warning or a refusal?
6. **Revocation latency.** When a principal's facet values change (role removed,
   segment lowered), what is the bound on how long a cached projection or an in-flight
   resolution may still reflect the old values?

---

## 15. Next artifact

Not another document. The next thing to build is:

1. **The facet contract in `helixor-core`** — declaration shape, admission rules,
   comparison semantics. No persistence.
2. **A cross-language conformance fixture set** that both the Java and Python
   implementations must pass, in CI. It must include at least one **coarse action
   spanning multiple entities across more than one bound source**, since that is the
   case where a per-binding implementation looks correct and a per-action one does not —
   plus a **multi-hop traversal** covering a denied edge, an unknown edge type, and a
   node re-entered by two paths where only one is permitted — with **a different filter
   on each entity in the path**, since a fixture that uses one filter throughout cannot
   tell a filter map from a single predicate.

The conformance test is the cheapest thing that makes the boundary real, because it
is what stops the two implementations from drifting. Build it before either side has
code worth drifting.
