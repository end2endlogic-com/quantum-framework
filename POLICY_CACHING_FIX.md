# Policy Caching Fix

## Problem
The `RuleContext` was caching all policies (both default system rules and database policies) in memory as an `@ApplicationScoped` bean. This meant that policies from the database collection were only loaded once during startup or when explicitly calling `reloadFromRepo()`. Any changes to policies in the database were not reflected in subsequent authorization checks.

## Solution
The solution separates the caching strategy for default system rules vs. database policies:

1. **Default System Rules**: Cached in memory (these rarely change)
2. **Database Policies**: Fetched fresh from the collection on every `checkRules()` invocation

## Changes Made

### 1. PolicyRepo.java
Added `getEffectiveRules()` method that:
- Takes default system policies as input
- Fetches fresh database policies from the collection via `getAllListIgnoreRules()` (bypasses security filters)
- Merges both into a single `Map<String, List<Rule>>`
- Sorts rules by priority
- Applies the same merge logic as `PolicyResource.getList()`

### 2. RuleContext.java
Key changes:
- Renamed `rules` field to `defaultSystemRules` to clarify it only holds system defaults
- Added `getEffectiveRulesForRequest()` method that calls `PolicyRepo.getEffectiveRules()` on each request
- Modified `getApplicableRulesForPrincipalAndAssociatedRoles()` to fetch fresh effective rules
- Updated `addRule()` to only add to `defaultSystemRules`
- Simplified `reloadFromRepo()` to only reload default system rules
- Updated all references to use the new field name

## Behavior

### Before
```
Startup: Load system rules + database policies → Cache in memory
Request 1: Use cached rules
Request 2: Use cached rules (stale if DB changed)
Request N: Use cached rules (stale if DB changed)
```

### After
```
Startup: Load system rules → Cache in memory
Request 1: Merge cached system rules + fresh DB policies → Evaluate
Request 2: Merge cached system rules + fresh DB policies → Evaluate
Request N: Merge cached system rules + fresh DB policies → Evaluate
```

## Performance Considerations
- Each authorization check now queries the database for policies
- This is acceptable because:
  1. Policy collections are typically small
  2. Authorization checks need to be accurate and up-to-date
  3. MongoDB queries are fast for small collections
  4. The alternative (stale policies) is a security risk

## Future Optimizations
If performance becomes an issue, consider:
1. Request-scoped caching (cache for duration of single HTTP request)
2. Time-based cache with short TTL (e.g., 5 seconds)
3. Event-driven cache invalidation
4. Change `RuleContext` from `@ApplicationScoped` to `@RequestScoped`

## Testing
To verify the fix:
1. Create a policy in the database
2. Make an authorization check - should see the new policy applied
3. Update the policy in the database
4. Make another authorization check - should see the updated policy applied
5. No need to call `/security/permission/policies/refreshRuleContext`
