# Fix: Rules Applied to Wrong Model Classes

## Problem Summary

When an `AccessListResolver` variable (e.g., `${accessibleLocationIds}`) is used in a rule's `andFilterString` for one model class (e.g., Location), the framework incorrectly applies that filter to queries for other model classes (e.g., UserProfile) that don't have rules using that variable.

### Root Cause

In `RuleContext.getFilters()` (line 1779-1847):
1. **Line 1783**: `checkRules(pcontext, rcontext)` matches rules by **area/domain/action only**, not by model class
2. **Line 1788**: `resolveVariableBundle(pcontext, rcontext, modelClass)` correctly resolves variables for the specific model class
3. **Lines 1790-1798**: ALL matched rules are processed, and their `andFilterString`/`orFilterString` are converted to filters using the resolved variables

**The Bug**: Rules matched by area/domain/action are applied to all model classes, even if the rule's filter string references model-specific variables that can't be resolved for other model classes.

### Example Scenario

1. Rule created for `location_hub:locations:list` with `andFilterString`: `id:^[${accessibleLocationIds}]`
2. Location LIST query → rule matches → filter applied correctly ✅
3. UserProfile query (internal, e.g., `userProfileRepo.getByUserId()`) → same area/domain/action or wildcard rule matches → Location's `andFilterString` incorrectly applied to UserProfile ❌
4. Framework tries to resolve `${accessibleLocationIds}` for UserProfile → resolver returns false (UserProfile not supported) → `IllegalStateException: Unresolved resolver variable` ❌

## Solution

Modified `RuleContext.getFilters()` to gracefully handle rules whose filter strings reference unresolvable variables:

1. **Wrap filter conversion in try-catch**: When converting `andFilterString`/`orFilterString` to filters, catch `IllegalStateException` that indicates unresolved variables
2. **Skip rules with unresolvable filters**: If a rule's filter string contains variables that can't be resolved for the current model class, skip that rule entirely
3. **Log debug information**: Log when rules are skipped due to unresolved variables (debug level to avoid noise)

### Implementation

**File**: `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java`

**Changes** (lines 1797-1846):
- Added try-catch blocks around `MorphiaUtils.convertToFilter()` calls
- Check if exception message contains "Unresolved resolver variable"
- If so, skip the rule and clear any filters added for it
- Continue processing other rules

### Key Points

- **Backward Compatible**: Rules that don't reference resolver variables continue to work as before
- **Fail-Safe**: Rules with unresolvable variables are skipped rather than causing errors
- **Model-Aware**: Rules are effectively scoped to model classes based on variable availability
- **No API Changes**: No changes to Rule model or Policy structure needed

## Testing

After implementing:
1. Create a rule for Location with filter: `id:^[${accessibleLocationIds}]`
2. Query Location → rule should apply correctly
3. Query UserProfile (internal query) → rule should be skipped (no error, no filter applied)
4. Verify logs show debug message when rule is skipped
5. Verify no `IllegalStateException` errors occur

## Related Issues

This fix addresses the framework bug where rules are matched by area/domain/action but their filter strings may be model-specific. The workaround in `TerritoryLocationResolver` (returning false for UserProfile when depth > 0) was exposing this bug by causing "Unresolved variable" errors. With this fix, those errors are handled gracefully and rules are properly scoped to model classes.

## Future Enhancements

Consider adding explicit model class scoping to Rules:
- Add optional `modelClass` or `modelClasses` field to Rule
- Filter rules by model class in `getFilters()` before processing filter strings
- This would make model scoping explicit rather than implicit based on variable availability
