### Filter Applicability Evaluator and Non‑Breaking API/Client Integration Design

#### Context and goals
- Treat rule filter strings (`andFilterString`, `orFilterString`, `joinOp`) as applicability constraints for a concrete resource during single‑resource checks.
- Keep existing DB‑side filtering in `RuleContext.getFilters(...)` for list queries unchanged.
- Introduce the evaluator and integrate into `RuleContext.checkRules` without breaking existing APIs or client code.
- Provide full traceability via `MatchEvent` additions.
- Ensure PermissionResource API compatibility and acl-client.js stability.


---
### Part 1 — Evaluator: responsibilities and flow

#### Responsibilities
1) Build a Json `facts` object from `ResourceContext` and optionally a domain resource instance.
2) Resolve variables with `RuleContext.resolveVariableBundle(pcontext, rcontext, modelClass)`.
3) Substitute variables into rule filter strings using the same conventions as `MorphiaUtils.convertToFilter(...)`.
4) Compile substituted strings into `Predicate<JsonNode>` using the existing predicate compiler.
5) Evaluate the predicates against `facts` and combine `AND/OR` per `joinOp` (default `AND`).
6) Return `Optional<Boolean>`: empty means “insufficient context or compiler unavailable” and must not block the decision pipeline.

#### Key method signatures
```
// In RuleContext (or a small helper component used by it)
public Optional<Boolean> evaluateFilterApplicability(
    PrincipalContext pcontext,
    ResourceContext rcontext,
    Rule rule,
    Class<? extends UnversionedBaseModel> modelClass, // optional. May be null
    Object resourceInstance,                           // optional. May be null
    FilterEvalTrace trace                              // output trace object (nullable)
)

// Trace DTO for logging and MatchEvent population
@Data
public class FilterEvalTrace {
  String andFilterString;
  String orFilterString;
  FilterJoinOp joinOp;      // default AND when both present
  boolean evaluated;        // true when predicate evaluation executed
  Boolean result;           // null if not evaluated
  String reason;            // why evaluation did not run or returned empty
}

// Variable substitution utility (kept small and consistent with Morphia path)
public Optional<String> substituteFilterVariables(
    String raw,
    MorphiaUtils.VariableBundle vars,
    StringBuilder reason
)

// Facts builder: shallow, safe
public Optional<JsonNode> buildFacts(ResourceContext rcontext, Object resourceInstance)
```

#### Facts shape (initial)
```
{
  "rcontext": {
    "area": "...",
    "functionalDomain": "...",
    "action": "...",
    "resourceId": "..."
  },
  "dataDomain": {
    "orgRefName": "...",
    "accountNum": "...",
    "tenantId": "...",
    "dataSegment": 0,
    "ownerId": "..."
  },
  "resource": { /* shallow fields from provided domain instance, optional */ }
}
```

#### Join semantics
- When both `andFilterString` and `orFilterString` present:
  - Default `joinOp` = `AND`
  - `AND`: `result = andResult && orResult`
  - `OR`:  `result = andResult || orResult`

#### Error handling
- Any failure in facts building, substitution, or compilation: log at DEBUG, return `Optional.empty()`.
- Never throw; never block the permission check.


---
### Part 2 — Integrating with RuleContext.checkRules

#### Overloads (non‑breaking)
```
// Existing signatures remain, delegating to the new overload with nulls
public SecurityCheckResponse checkRules(PrincipalContext pcontext, ResourceContext rcontext)
public SecurityCheckResponse checkRules(PrincipalContext pcontext, ResourceContext rcontext, RuleEffect defaultFinalEffect)

// New overload to enable in‑memory filter applicability for concrete resources
public SecurityCheckResponse checkRules(
    @Valid @NotNull PrincipalContext pcontext,
    @Valid @NotNull ResourceContext rcontext,
    Class<? extends UnversionedBaseModel> modelClass, // optional
    Object resourceInstance,                           // optional
    RuleEffect defaultFinalEffect                      // optional in a second overload if desired
)
```

#### Rule loop integration (ordering)
1) URI match as today → if matched proceed.
2) Precondition script (existing) → if false: mark rule `NOT_APPLICABLE`, continue.
3) NEW: call `evaluateFilterApplicability(...)`.
   - Present and `false` → mark rule `NOT_APPLICABLE`, record trace in `MatchEvent`, continue.
   - Empty → record trace with `evaluated=false`, `reason`, continue (defer to DB filters if repository path is used downstream).
   - Present and `true` → proceed.
4) Postcondition script (existing).
5) Determine effect as today, honor `finalRule` only when the rule actually applies (i.e., passed filters and postcondition if present).


---
### Part 3 — MatchEvent traceability (additive fields)
Add fields for auditability; these are additive and should not break existing consumers:
```
String filterAndString;
String filterOrString;
String filterJoinOp;
boolean filterEvaluated;
Boolean filterResult;   // null when not evaluated
String filterReason;    // message when not evaluated or short‑circuited
```
Populate on each rule processing path. Keep logs at DEBUG.


---
### Part 4 — PermissionResource API changes (non‑breaking)

#### Current endpoints of interest
- POST `/system/permissions/check` (and variants)
- POST `/system/permissions/check-with-index` returning both `SecurityCheckResponse` and `RuleIndexSnapshot` for clients including `acl-client.js`.

#### Additive request fields (optional)
For endpoints intended to evaluate a single concrete resource (not list endpoints), add optional fields to the JSON request:
```
// New optional fields on CheckRequest (and any sibling request DTOs)
String modelClass;          // fully qualified class name or entity name resolvable to model
Map<String, Object> resource; // shallow snapshot of the domain resource instance
Boolean enableFilterEval;   // default true when both modelClass & resource present; explicit false to bypass
```
Notes:
- If omitted or `enableFilterEval=false`, server behavior is unchanged.
- When present, the server attempts evaluator path; on any issue it falls back without error.

#### Response additions (optional, additive)
No schema changes required for existing decision fields. We only add `MatchEvent` trace fields (see Part 3) inside `SecurityCheckResponse.matchEvents[]`. Existing clients that ignore unknown fields remain unaffected.

#### Server behavior
- PermissionResource constructs `PrincipalContext`/`ResourceContext` as today.
- If `modelClass` resolves and `resource` is present (or if the server can look up the resource by `resourceId`), call the new `RuleContext.checkRules(...)` overload with these parameters and `defaultFinalEffect` unchanged.
- Otherwise, delegate to existing `checkRules(...)` signature.


---
### Part 5 — JavaScript client (acl-client.js) implications

The `acl-client.js` library consumes the rule index snapshot and an optional precomputed matrix. The proposed changes are server‑side only for single resource checks and do not alter the index snapshot shape. Therefore:

- No client code change is required for basic matrix decisions (`decide` / `decideOutcome`).
- The new `MatchEvent` fields are part of the check response, not the snapshot matrix; `acl-client.js` does not parse `MatchEvent`s today and can ignore them.
- Optional enhancement (future): provide a small helper in `acl-client.js` to render filter applicability traces if UIs POST to the check endpoint and want to display “why not applicable.” This is non‑blocking and can be scheduled later.


---
### Part 6 — List endpoints and getFilters

- No changes to `RuleContext.getFilters(...)`.
- List endpoints must continue to call `getFilters(...)` to construct DB‑side constraints based on rule filter strings and variable resolution.
- The evaluator is only used when a concrete resource instance is present (single‑resource checks) and does not replace DB filtering.


---
### Part 7 — Error handling and fallbacks

- If facts building, variable substitution, or predicate compilation fails → DEBUG log, return `Optional.empty()`.
- `checkRules` proceeds as today; no change to outcomes for callers that do not supply resource/modelClass, and no hard failure when evaluator cannot run.


---
### Part 8 — Unit/Integration tests

1) Evaluator unit tests:
   - `andFilterString` match / non‑match
   - `orFilterString` variants
   - Both present with `joinOp=AND` and `joinOp=OR`
   - Missing variables → `Optional.empty()` with reason
2) `RuleContext.checkRules` integration:
   - Precondition false short‑circuits before evaluator
   - Evaluator false → `NOT_APPLICABLE`, final rule not triggered
   - Evaluator empty → behavior unchanged, DB filter path unaffected
   - Postcondition runs only after evaluator passes
   - Final rule respected once filters pass
3) PermissionResource API:
   - Requests without new fields remain identical in behavior
   - Requests with `modelClass` + `resource` enable evaluator path
4) JS asset presence:
   - `AclClientTest` remains green; no changes needed.


---
### Part 9 — Rollout checklist

- [ ] Add evaluator, substitution utility, and facts builder (server)
- [ ] Add new `RuleContext.checkRules(...)` overload, keep existing methods delegating
- [ ] Extend `MatchEvent` with additive fields for filter trace
- [ ] Extend `PermissionResource.CheckRequest` (and any sibling DTOs) with optional `modelClass`, `resource`, `enableFilterEval`
- [ ] Implement server side wiring to pass modelClass/resource to `RuleContext.checkRules` when present
- [ ] Update developer docs with guidance for single‑resource vs list endpoints
- [ ] Add tests as described above
- [ ] Verify logs at DEBUG for evaluator fallbacks
- [ ] Consider future small LRU cache keyed by substituted string (TODO)


---
### Appendix — Data shapes

#### CheckRequest (additive)
```
{
  "identity": "user:123",          // existing
  "roles": ["role:admin"],        // existing
  "realm": "default",             // existing
  "orgRefName": "acme",           // existing
  "accountNumber": "001",         // existing
  "tenantId": "t1",               // existing
  "dataSegment": 0,                 // existing
  "ownerId": "user:123",          // existing
  "scope": "...",                 // existing
  "area": "SALES",                // existing
  "functionalDomain": "ORDER",    // existing
  "action": "READ",               // existing
  "resourceId": "ORD-42",         // existing

  // NEW OPTIONAL FIELDS (non‑breaking)
  "modelClass": "com.example.Order",
  "resource": { "id": "ORD-42", "tenantId": "t1", "ownerId": "user:123" },
  "enableFilterEval": true
}
```

#### MatchEvent (additive)
```
{
  // existing fields ...
  "filterAndString": "dataDomain.ownerId:${principalId}",
  "filterOrString":  null,
  "filterJoinOp":    "AND",
  "filterEvaluated": true,
  "filterResult":    false,
  "filterReason":    "ownerId missing from facts" // or "predicate compiler unavailable" etc.
}
```

#### RuleContext.checkRules overloads (Java)
```
SecurityCheckResponse checkRules(PrincipalContext pc, ResourceContext rc);
SecurityCheckResponse checkRules(PrincipalContext pc, ResourceContext rc, RuleEffect defaultFinalEffect);
SecurityCheckResponse checkRules(PrincipalContext pc, ResourceContext rc,
                                 Class<? extends UnversionedBaseModel> modelClass,
                                 Object resourceInstance,
                                 RuleEffect defaultFinalEffect);
```

This design file captures the evaluator, server integration, PermissionResource request/response considerations, and client implications with a non‑breaking rollout path.
