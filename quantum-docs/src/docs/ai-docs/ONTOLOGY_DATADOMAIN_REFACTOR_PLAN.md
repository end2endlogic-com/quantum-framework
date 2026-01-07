### Ontology DataDomain-Scoped Refactor Plan (Breaking Change)

#### Summary
Move ontology storage, queries, and inference from `tenantId` scoping to full `DataDomain` scoping within a single MongoDB realm (database). This eliminates cross-organization/account leakage and collisions. This is a breaking change; no backward-compatibility shims will be provided.

#### Finalized Decisions
- Owner scoping: Do NOT scope ontology by `ownerId`. Ontology is user-independent.
- Cross-segment inference: Disallow. Property chains must NOT cross different `dataSegment` values within the same org/account/tenant.
- Backward compatibility: Not required. Proceed with a clean breaking change.
- Performance: Additional composite index fields are acceptable.

---

### Objectives
1. Ensure uniqueness and isolation of ontology edges per `DataDomain`:
   - `orgRefName`, `accountNum`, `tenantId`, `dataSegment`.
2. Ensure all queries are filtered by full `DataDomain` (not only `tenantId`).
3. Ensure inference and materialization propagate and persist full `DataDomain`.
4. Prevent cross-org/account/segment leakage in providers, repos, resources.

### Scope (Code Areas)
- quantum-ontology-mongo
  - `com.e2eq.ontology.model.OntologyEdge`
  - `com.e2eq.ontology.repo.OntologyEdgeRepo` (+ tests)
- quantum-ontology-core
  - `com.e2eq.ontology.core.EdgeRecord`
  - `com.e2eq.ontology.core.OntologyMaterializer`
  - `com.e2eq.ontology.core.reasoner.*` (ensure DataDomain propagation)
- Providers
  - `UserIdMatchingEdgeProvider` and other SPI providers
- Resource layer / query rewriters
  - `OntologyAwareResource`, `ListQueryRewriter`
- Framework integration
  - Use `RuleContext.getFilters(...)` with `SecurityContext` for all repository reads
- Migration script (Mongo shell/Node) for backfilling `dataDomain` on existing edges and rebuilding indexes

---

### Implementation Phases

#### Phase 1: Model & Index Updates (Week 1–2)
1. Update edge uniqueness index (no `ownerId`).
   - File: `quantum-ontology-mongo/.../OntologyEdge.java`
   - Change:
     - Remove `uniq_tenant_src_p_dst` (tenant-only)
     - Add `uniq_domain_src_p_dst` unique index on:
       - `dataDomain.orgRefName`, `dataDomain.accountNum`, `dataDomain.tenantId`, `dataDomain.dataSegment`, `src`, `p`, `dst`
   - Add read-optimizing indexes, e.g. `idx_domain_p_dst` and others as needed.

2. Replace `tenantId` with `DataDomain` in edge DTO used for batch upserts:
   - File: `quantum-ontology-core/.../EdgeRecord.java`
   - `tenantId` → `DataDomain dataDomain`

#### Phase 2: Repository Signature Changes (Week 2–3)
3. Update `OntologyEdgeRepo` signatures to accept `DataDomain` instead of `tenantId` (breaking):
   - Examples:
     - `upsert(DataDomain dd, String srcType, String src, String p, String dstType, String dst, ...)`
     - `srcIdsByDst(DataDomain dd, String p, String dst)`
     - `findBySrc(DataDomain dd, String src)`
     - `deleteBySrc(DataDomain dd, String src, boolean inferredOnly)`
   - Internals: filter by full `dataDomain` fields + business predicates.

#### Phase 3: Integrate RuleContext Filtering (Week 3–4)
4. For read paths that are principal/resource-sensitive, derive filters via `RuleContext` (MorphiaRepo pattern):
   - Inject `RuleContext` and call `ruleContext.getFilters(filters, pc, rc, OntologyEdge.class)`.
   - Replace public APIs that previously accepted `tenantId` with versions that:
     - Either accept `DataDomain` explicitly, or
     - Derive it from `SecurityContext` via `RuleContext` when appropriate.

#### Phase 4: Edge Providers (Week 4)
5. Update `UserIdMatchingEdgeProvider`:
   - Method: `edges(String realmId, DataDomain dataDomain, Object entity)`
   - Add filters on `dataDomain.orgRefName`, `dataDomain.accountNum`, `dataDomain.tenantId` (and segment as applicable) when querying `UserProfile`.
   - Prevent cross-org/account matches (same email/userId across orgs must NOT link).

#### Phase 5: Materializer Updates (Week 5)
6. Update `OntologyMaterializer` to accept and propagate `DataDomain`:
   - Method: `apply(DataDomain dataDomain, String entityId, String entityType, List<Reasoner.Edge> explicitEdges)`
   - Ensure inferred edges inherit the exact same `DataDomain`.
   - Batch upserts use `EdgeRecord` with `dataDomain`.

#### Phase 6: Resource Layer & Query Rewriter (Week 5–6)
7. Update `OntologyAwareResource` and `ListQueryRewriter`:
   - Extract `DataDomain` from `SecurityContext`/`PrincipalContext`.
   - Replace `tenantId` params with `DataDomain`.
   - All ontology lookups must be DataDomain-scoped.

#### Phase 7: Migration (Week 6)
8. Write and run migration script per realm DB:
   - Backfill `dataDomain` on edges lacking full context by reading source entities’ `dataDomain`.
   - Drop old `uniq_tenant_src_p_dst`; create `uniq_domain_src_p_dst`.
   - Validate counts and uniqueness.

---

### Testing Strategy

#### Unit Tests
- `OntologyEdgeRepo` DataDomain isolation:
  - Insert identical `(src,p,dst)` into two different `(orgRefName, accountNum)` with same `tenantId`.
  - Ensure queries scoped to Org A return only Org A edges.
  - Ensure unique index does NOT collide across distinct DataDomains.

#### Integration Tests
- Multi-account, same-tenant scenario:
  - Create Account A and B under same realm/tenant.
  - Create `Project -> Customer` edge in each.
  - Verify no collisions and no cross-account leakage.

- Edge Provider test (`UserIdMatchingEdgeProvider`):
  - Credential in Org A and UserProfile in Org B with same email.
  - Verify provider does NOT create cross-org edge.

- Materializer/inference:
  - Verify inferred edges remain within the same `dataSegment`; no cross-segment property chain results.

#### Migration Validation
- Pre/post edge counts.
- Zero unique index violations after rebuild.
- Sample-based verification that backfilled `dataDomain` matches source entities.

---

### Risk & Mitigations
- Write performance impact due to wider unique index: acceptable; monitor write latency and index build time.
- Widespread API breakage due to signature changes: addressed via coordinated refactor and compilation-driven fixes.
- Missing `SecurityContext` in some code paths: enforce presence where RuleContext-derived filters are required; fail fast with clear exceptions.

### Deliverables
1. Updated models, repos, providers, materializer, and resources with DataDomain scoping.
2. New indexes deployed and validated per realm DB.
3. Migration script and execution report.
4. Unit/integration test suites passing.

### Timeline (Target)
- Week 1–2: Phase 1 (Model/index) complete
- Week 2–3: Phase 2 (Repo signatures) complete
- Week 3–4: Phase 3 (RuleContext integration) complete
- Week 4: Phase 4 (Providers) complete
- Week 5: Phase 5 (Materializer) complete
- Week 5–6: Phase 6 (Resource layer) complete
- Week 6: Phase 7 (Migration) complete; begin full test pass
- Week 7: Validation and sign-off

### Out of Scope
- Owner-based filtering (explicitly excluded)
- Cross-segment inference (explicitly disallowed)

### Operational Notes
- Run migration per realm sequentially to limit index build contention.
- Ensure application versions that assume new schema are deployed only after migration/index rebuild completes.
