# Fix: AccessListResolver Returns Empty When Rule Filter is Applied

## Problem Description

When an `AccessListResolver` (specifically `TerritoryLocationResolver`) is used in a rule's `andFilterString` parameter, the resolved variable becomes empty even though logs show the resolver is being invoked correctly when hitting the list endpoint directly.

**Example:**
- Rule filter: `id:^[${accessibleLocationIds}]`
- Without the rule: Resolver logs show correct IDs
- With the rule: `accessibleLocationIds` always returns empty

## Root Cause

The issue is caused by recursive resolution when repository calls inside the resolver trigger security filters:

1. `getFilters()` calls `resolveVariableBundle()` for Location model
2. `TerritoryLocationResolver.resolve()` is invoked, increments `RESOLVE_DEPTH`
3. Inside `resolve()`, `territoryRepo.getAllLocationsForTerritory()` queries Location models
4. That query triggers `getFilters()` again (via MorphiaRepo security filtering)
5. `getFilters()` calls `resolveVariableBundle()` again
6. `supports()` sees `RESOLVE_DEPTH > 0` and returns `false` (to prevent recursion)
7. Variable is not populated → filter becomes `id:^[]` (empty)

The depth guard in `supports()` prevents the resolver from running when it's already resolving, but this causes the variable to be empty in recursive calls.

## Solution

Modify `TerritoryLocationResolver` to:
1. Track the model class being resolved (not just depth)
2. Only skip resolution when resolving the **same** model class recursively
3. Cache the result during resolution so recursive calls can return the cached value immediately

This allows:
- Recursive calls for the same model class to return cached result (no infinite loop)
- Different model classes to resolve independently (e.g., resolving for Job while Location is being resolved)

## Implementation Steps

### Step 1: Update Thread-Local Variables

Add a model class tracker and result cache:

```java
// Track both depth and the model class being resolved
private static final ThreadLocal<Integer> RESOLVE_DEPTH = ThreadLocal.withInitial(() -> 0);
private static final ThreadLocal<Class<?>> RESOLVING_MODEL_CLASS = new ThreadLocal<>();
private static final ThreadLocal<Collection<?>> CACHED_RESULT = new ThreadLocal<>();
```

### Step 2: Update `supports()` Method

Modify to allow resolution when already resolving (so `resolve()` can return cached result):

```java
@Override
public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class modelClass) {
    Log.info("TerritoryLocationResolver: Checking support for model class: " +
            (modelClass != null ? modelClass.getName() : "null"));

    // If we're already resolving for the same model class, still return true
    // so resolve() can return the cached result
    Class<?> currentlyResolving = RESOLVING_MODEL_CLASS.get();
    if (RESOLVE_DEPTH.get() > 0 && currentlyResolving != null && modelClass != null 
            && currentlyResolving.equals(modelClass)) {
        Log.info("TerritoryLocationResolver: Already resolving for model class: " + 
                modelClass.getName() + ", will return cached result");
        // Continue to return true below so resolve() is called
    }

    if (modelClass == null || rctx == null)
        return false;

    // Apply only on LIST/QUERY operations
    String action = rctx.getAction();
    if (!"list".equals(action) && !"query".equals(action))
        return false;

    // Apply to specific model classes
    String name = modelClass.getName();
    return name.equals("com.movista.models.Job")
            || name.equals("com.movista.models.Location")
            || name.equals("com.movista.models.JobPlan");
}
```

### Step 3: Update `resolve()` Method

Add caching logic at the start and end:

```java
@Override
public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class modelClass) {
    Log.info("TerritoryLocationResolver: Resolving accessible locations based on territory assignments");

    // Check if we're already resolving for this model class - if so, return cached result
    Class<?> currentlyResolving = RESOLVING_MODEL_CLASS.get();
    if (RESOLVE_DEPTH.get() > 0 && currentlyResolving != null && modelClass != null 
            && currentlyResolving.equals(modelClass)) {
        Collection<?> cached = CACHED_RESULT.get();
        if (cached != null) {
            Log.info("TerritoryLocationResolver: Returning cached result for model class: " + 
                    modelClass.getName());
            return cached;
        }
    }

    // Mark entry - track depth and model class
    RESOLVE_DEPTH.set(RESOLVE_DEPTH.get() + 1);
    Class<?> previousModelClass = RESOLVING_MODEL_CLASS.get();
    RESOLVING_MODEL_CLASS.set(modelClass);
    Collection<?> previousCached = CACHED_RESULT.get();
    
    try {
        // ... existing resolution logic ...
        
        // At the end, cache the result before returning
        CACHED_RESULT.set(locationIds);
        return locationIds;
        
    } catch (Exception e) {
        Log.errorf(e, "TerritoryLocationResolver: Error resolving accessible locations");
        return Collections.emptySet();
    } finally {
        // Always decrement and restore previous state
        RESOLVE_DEPTH.set(RESOLVE_DEPTH.get() - 1);
        if (RESOLVE_DEPTH.get() == 0) {
            // If depth is back to 0, clear everything
            RESOLVING_MODEL_CLASS.remove();
            CACHED_RESULT.remove();
        } else {
            // Otherwise restore previous state (for nested resolutions)
            RESOLVING_MODEL_CLASS.set(previousModelClass);
            CACHED_RESULT.set(previousCached);
        }
    }
}
```

## File to Modify

`/Users/ddayton/projects/jbackend/amp-rest/src/main/java/com/movista/rest/resolvers/TerritoryLocationResolver.java`

## Testing

After implementing:
1. Add a rule with filter: `id:^[${accessibleLocationIds}]`
2. Hit the location LIST endpoint
3. Verify logs show resolver being called and returning IDs
4. Verify the filter is applied correctly (only accessible locations are returned)
5. Verify no infinite recursion occurs

## Key Points

- The depth guard prevents infinite loops but was too aggressive
- Caching allows recursive calls to reuse the same result
- Model class tracking ensures we only cache/return for the same model type
- Different model classes can resolve independently without interference
