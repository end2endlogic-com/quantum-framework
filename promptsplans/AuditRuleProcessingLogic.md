### Security Rules Engine Audit Report

#### Scope
- Components reviewed: `RuleContext`, `RuleIndex`, `Rule` model, `Policy` model, and `SecurityFilter` (plus `IdentityRoleResolver`).
- Goal: Confirm that rule processing starts from a global default (allow/deny), expands principal into userId + roles, matches by principal/resource contexts, applies priority and finality to conclude ALLOW or DENY. Identify logic issues and recommend improvements.

---

### How the engine works today (observed)

1. Policy and rule loading
- `RuleContext.ensureDefaultRules()` initializes by calling `reloadFromRepo(defaultRealm)`; on failure falls back to built‑in system rules via `addSystemRules()`.
- `reloadFromRepo(realm)` clears in‑memory rules, adds system rules, then loads `Policy` objects. For each `Rule` it derives the identity from `rule.securityURI.header.identity` or `policy.principalId`; if missing it skips.
- Rules are stored in a map keyed by identity (userId or role): `Map<String, List<Rule>> rules`.
- Each identity’s list is sorted by `priority` ascending. Optional compiled `RuleIndex` is built if enabled.

2. Principal and resource context
- `SecurityFilter` constructs `ResourceContext` via annotations (`@FunctionalMapping`/`@FunctionalAction`) or via path parsing; action may be mapped from HTTP verb. It builds `PrincipalContext` from JWT or `SecurityIdentity` + persistent credentials, resolving roles through token roles, credential roles, and `UserGroup` roles.
- ThreadLocal `SecurityContext` is set for request processing and cleared on response.

3. Rule evaluation
- `RuleContext.checkRules(pcontext, rcontext, defaultFinalEffect)` starts with a default final effect (default is `DENY` via the two‑arg overload).
- Candidate rules are gathered: all rules for `pcontext.userId` and for each role in `pcontext.roles` (or via the compiled `RuleIndex` if enabled). Candidates are sorted by `priority` ascending.
- It builds expanded principal URIs (one per role, then the userId). For each rule, if wildcard match succeeds against any expanded URI, it:
    - Runs `postconditionScript` if present; if true, `finalEffect` is set to the rule’s `effect`. If no script, effect is directly applied.
    - Stops evaluating if `finalRule == true` ("final"). Otherwise proceeds, allowing later rules to override `finalEffect`.
- If no rule matches, the `defaultFinalEffect` remains in force.

4. Filters for data access
- `RuleContext.getFilters(...)` computes `SecurityCheckResponse` then translates `RuleResult`s with `AND`/`OR` filter strings into Morphia `Filter`s, combining per rule with `joinOp` and adding to the provided filter list. Variable substitution comes from a `VariableBundle` resolved via `AccessListResolver`s.

5. Indexing (optional)
- `RuleIndex` pre‑indexes rules along identity/area/domain/action with wildcard branches and preserves priority ordering; it double‑checks full wildcard match of a synthesized principal URI before returning candidates.

6. Built‑in system rules
- Grant full ALLOW for `system` principal/role in the Security area.
- Provide a base ALLOW rule for role `user` in any area/domain/action restricted by filters (e.g., `dataDomain.ownerId:${principalId} && dataDomain.dataSegment:#0`). Add a specific DENY final rule for role `user` performing `DELETE` in `Security` area.

---

### Alignment with the intended model
- Global default decision: Present via `checkRules(..., defaultFinalEffect)` (the default path uses `DENY`), so an explicit initial global rule is not required but can be modeled.
- Principal context expansion: Implemented — `PrincipalContext` contains userId and roles; roles are resolved from token, credentials, and user groups (through `IdentityRoleResolver`).
- Matching by principal/resource context: Implemented via expanded principal URIs and wildcard matching against each `Rule.securityURI`.
- Priority and finality: Implemented — rules are processed by ascending `priority`; `finalRule` stops further evaluation.
- Final decision: ALLOW or DENY is produced. When multiple rules match and no final rule intervenes, later (higher priority value) rules can overwrite earlier effects.

---

### Findings and potential issues

1) Precondition scripts are never evaluated (logic gap)
- `Rule` has `preconditionScript`, but `RuleContext.checkRules()` only evaluates `postconditionScript`. Comments say “check the precondition and post conditions scripts,” but only post is used.
- Risk: Rules intended to gate costly checks or do early elimination via pre‑conditions won’t behave as designed.
- Recommendation: Add precondition evaluation before effect determination; if pre returns false, mark as NOT_APPLICABLE and skip post and effect.

2) Script execution is overly permissive and not sandboxed
- Both `RuleContext.runScript(...)` and `SecurityFilter.runScript(...)` create Graal `Context` with `.allowAllAccess(true)` and no timeout or resource limits.
- Risk: Arbitrary code execution, data exfiltration, DoS via long/recursive scripts.
- Recommendation: Use a constrained `Context` (no host access, limit CPU/mem/time, disable polyglot I/O), precompile/validate expressions, or replace with a safe expression DSL.

3) Identity case sensitivity mismatch can cause rule misses
- Rules are keyed by `identity` case‑sensitively (`rules` map), while matching URIs use `IOCase.INSENSITIVE`.
- If a `Policy.principalId` is saved as `Admin` and runtime role resolves as `admin`, `rulesForIdentity("admin")` won’t find the list and applicable rules are lost.
- Recommendation: Normalize identities (e.g., to lower‑case) at load time and on lookup; enforce a casing convention across the system.

4) OwnerId set to role name for role‑based URIs
- `expandURIPrincipalIdentities` and `createURLForIdentity` set `body.ownerId = identity`. For role identities, this means `ownerId` becomes the role string.
- If scripts or filters rely on `rcontext.ownerId` equating to the actual resource owner userId, they could misbehave when evaluating role URIs first.
- Recommendation: For role identities, consider setting `ownerId` to the principal’s userId (or `*`), and expose the identity (role vs userId) via a separate field. At minimum, document this quirk and ensure filters/scripts rely on `${principalId}` or explicit variables instead of `ownerId` for principal identity comparison.

5) Rule evaluation overwrite semantics may not be obvious
- Ascending priority ordering means smaller numbers are processed first; later rules (higher priority numbers) overwrite `finalEffect` unless a prior rule is final.
- This is “last write wins unless final.” Some policy engines prefer “first match wins” or “highest priority wins.”
- Recommendation: Document current semantics clearly. Optionally add a config to switch between “first match wins” and the current behavior. Consider reversing priority semantics (higher priority first) for more intuitive behavior.

6) ResourceContext path parsing bug (exactly 3 path segments)
- In `SecurityFilter.determineResourceContext`, when `tokenCount == 3`, the code sets both `area` and `functionalDomain` to the same token and uses the second token as action. This is inconsistent with the `>3` branch and with the standard convention `/area/domain/action`.
- Recommendation: Fix the `==3` case to: `area = first`, `functionalDomain = second`, `action = third`.

7) Potential mixing of ALLOW and DENY filter contributions
- `RuleContext.getFilters(...)` constructs filters from all `RuleResult`s except those with `NOT_APPLICABLE`. It does not check the rule’s `effect`. DENY rules can thus add filters, which is typically undesirable (DENY should subtract, not add).
- Recommendation: Only add filters from rules with `effect == ALLOW`. If supporting DENY filters, implement a separate subtractive filter mechanism and apply it with correct precedence.

8) AND/OR filter accumulation across rules
- In `getFilters(...)`, `andFilters` and `orFilters` lists are reused across rule iterations and only partially cleared. This can lead to cross‑rule accumulation and unintended composite filters.
- Recommendation: Reset `andFilters`/`orFilters` for each rule (or build a per‑rule combined filter first, then merge into the final list). Add unit tests covering multiple rules with various `joinOp`s.

9) Concurrency and visibility of `rules` map
- `reloadFromRepo` is `synchronized` and calls `clear()` then mutates the shared `rules` map. Readers of `rules` (e.g., `getApplicableRules...`) are unsynchronized.
- Risk: Requests could observe a partially rebuilt map during reload, or observe different identities lists mid‑mutation.
- Recommendation: Build new structures off‑thread (local map), sort, then publish via an atomic swap of a volatile reference (or use `ConcurrentHashMap` and publish a new immutable snapshot). `compiledIndex` is already volatile.

10) Error resilience and logging nits
- A minor logging typo: `Log.warnf("Rule:%s dod mpt jave an identity specified:", r.toString())` — should read “does not have an identity specified.”
- `reloadFromRepo`’s context bootstrap swallows exceptions silently; consider clearer logs with outcomes. Also ensure realm used for hydration is correct when none is passed.

11) `SecurityFilter.runScript` for impersonation
- Same sandboxing/defaults concerns as #2. Additionally it requires both `subject` and `userId`, and throws if both `X-Impersonate-Subject` and `X-Impersonate-UserId` are present — good validation. Consider adding timeouts and limiting evaluation.

12) Index parity and fallback
- `RuleIndex` respects identity + area/domain/action hierarchy with wildcard branches and does a final wildcard match on synthesized URIs using `IOCase.INSENSITIVE`. This is good. Ensure identity normalization is applied here as well to avoid missing indexed rules.

13) Default/global rule requirement
- You do not strictly need an explicit global rule: the API supports a default final effect (`ALLOW` or `DENY`) via `checkRules`. Built‑in system rules provide base allowances/denials for system and `user` role. If you prefer a policy‑only approach, you could seed an explicit global catch‑all rule per realm.

---

### Recommendations (prioritized)

High priority
1. Implement `preconditionScript` support in `checkRules` prior to effect assignment; on false → NOT_APPLICABLE (skip effect and post).
2. Sandbox and limit script execution:
    - Use `Context.newBuilder("js").allowAllAccess(false)`.
    - Restrict host access (no file/network/classloader), memory limit, and timeouts (interrupt/engine cancel after N ms).
    - Consider a safer expression engine for boolean checks (e.g., MVEL with whitelist, Aviator, SpEL) if JS is not required.
3. Normalize identities to a canonical case (e.g., lower‑case) both when loading rules/policies and when looking up by identity/role at runtime. Update `RuleIndex` builder accordingly.
4. Fix `determineResourceContext` for exactly three path segments to correctly map `/area/domain/action`.
5. In `getFilters(...)`, only include filters from rules whose determined effect is `ALLOW`. Add tests for mixed ALLOW/DENY rules.

Medium priority
6. Reset `andFilters`/`orFilters` per rule or refactor to produce one combined filter per rule first; then merge by `joinOp` with the overall filter list.
7. Decide and document rule ordering semantics. If desired, switch to “highest priority wins” or “first match wins,” and gate with a config flag to avoid breaking existing policies.
8. Improve reload publishing: build a new `Map<String,List<Rule>>` and swap a volatile reference to avoid readers seeing partial state. Optionally replace lists with immutable collections.
9. Revisit `ownerId` population for role identities: set to principal userId or `*`, and pass role identity separately to scripts via bindings (e.g., `principalId`, `roles`), to avoid confusing owner checks.

Low priority
10. Clean up logging typos and enhance startup/reload logs. Add version information to `SecurityCheckResponse` for traceability (policy version, index version).
11. Add more unit tests:
- Identity case normalization (Admin/admin).
- Exactly three path segments resource context mapping.
- Pre/post script behaviors.
- Mixed ALLOW/DENY filter construction and `joinOp` variations.
- Concurrency test for reload vs. concurrent checks.

---

### Deviations from the intended purpose/process
- Missing precondition handling (#1) deviates from the described two‑phase rule evaluation.
- Potential identity case mismatch (#3) can cause intended role/user rules to be skipped.
- Role‑based URI ownerId assignment (#4/#9) may deviate from “ownerId is the userId” expectations in scripts/filters.
- Unfixed path parsing (#6) can misclassify resource requests, bypassing intended rules for those endpoints.

---

### Suggested next steps (incremental)
1) Quick fixes (1–2 PRs):
- Add precondition evaluation. Normalize identities. Fix 3‑segment path parsing. Limit script context access and add timeouts.
2) Filter semantics tightening:
- Only accumulate ALLOW filters; reset per rule; add tests.
3) Reload publication & immutability:
- Build/swap immutable rule map and rebuild index; expose `policyVersion` and `indexVersion` in `SecurityCheckResponse`.
4) Semantics decision:
- Confirm desired priority model (last‑wins vs first‑wins vs highest‑priority‑wins) and implement accordingly, gated by config.

If you’d like, I can open a focused PR that implements the high‑priority items with accompanying unit tests.
