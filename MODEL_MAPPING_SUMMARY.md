# Model as Single Source of Truth - Summary

## What Changed

The framework now automatically extracts `@FunctionalMapping` from model classes, eliminating the need to duplicate annotations on REST resources.

## How It Works

1. **ModelMappingExtractor** filter runs before SecurityFilter
2. Extracts model class from `BaseResource<T, R>` generic parameter
3. Reads `@FunctionalMapping` from model class
4. Injects into request context
5. **SecurityFilter** uses model mapping as priority #2 (after method-level overrides)

## Priority Order

1. **Method-level** `@FunctionalMapping` (rare overrides)
2. **Model class** `@FunctionalMapping` ← **SINGLE SOURCE OF TRUTH**
3. **Class-level** `@FunctionalMapping` (non-BaseResource classes)
4. **URL parsing** (backward compatible fallback)

## Usage

### Standard Case (Zero Configuration)

```java
// Model - ONLY place to define area/domain
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

// Resource - NO annotation needed
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    public LocationResource(LocationRepo repo) {
        super(repo);
    }
}

// Works with ANY URL structure
@Path("/api/v2/locations")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> { }

// Even this works
@Path("/completely/different/path")
public class LocationResourceV3 extends BaseResource<Location, LocationRepo> { }
```

All three resources above use `area="people_hub", domain="location"` from the Location model.

### Override for Special Endpoints (Rare)

```java
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    
    // Standard endpoints use model's area/domain automatically
    
    @POST
    @Path("/admin/purge")
    @FunctionalMapping(area = "admin", domain = "location_admin")
    @FunctionalAction("PURGE")
    public Response adminPurge() {
        // Special admin endpoint with different area/domain
    }
}
```

## Benefits

✅ **Single Source of Truth**: Model annotation is the ONLY place to define area/domain

✅ **Zero Duplication**: No need to annotate REST resources

✅ **Automatic**: Works for all `BaseResource` subclasses

✅ **URL Independent**: Change URLs freely without affecting authorization

✅ **Maintainable**: Update model annotation once, affects all resources

✅ **Type Safe**: Uses generic type parameter, no reflection magic at runtime

## Migration

### No Changes Needed!

If your models already have `@FunctionalMapping`, everything works automatically:

```java
// Existing model
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

// Existing resource
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> { }

// ✅ Works automatically - no changes needed!
```

### Remove Duplicate Annotations (Optional)

If you previously added annotations to REST resources, you can remove them:

```java
// Before
@Path("/api/locations")
@FunctionalMapping(area = "people_hub", domain = "location") // ← Remove this
public class LocationResource extends BaseResource<Location, LocationRepo> { }

// After
@Path("/api/locations")
public class LocationResource extends BaseResource<Location, LocationRepo> { }
// ✅ Uses Location model's annotation automatically
```

## Files Modified

1. **BaseResource.java**
   - Added `modelClass` field
   - Added `extractModelClass()` method
   - Added `getModelFunctionalMapping()` method

2. **ModelMappingExtractor.java** (NEW)
   - JAX-RS filter that extracts model mapping
   - Runs before SecurityFilter
   - Injects mapping into request context

3. **SecurityFilter.java**
   - Updated priority order
   - Model mapping now priority #2
   - Added debug logging

## Testing

```bash
# All these should work identically:
curl http://localhost:8080/people_hub/location/list
curl http://localhost:8080/api/v2/locations/list
curl http://localhost:8080/any/url/structure/list

# All resolve to: area="people_hub", domain="location", action="LIST"
```

## Debug Logging

Enable to see which mapping is used:

```properties
quarkus.log.category."com.e2eq.framework.rest.filters".level=DEBUG
```

Look for:
```
Extracted model mapping: area=people_hub, domain=location from LocationResource
Using model class @FunctionalMapping: area=people_hub, domain=location
```

## Edge Cases

### Non-BaseResource Classes

For resources that don't extend BaseResource, use class-level annotation:

```java
@Path("/custom")
@FunctionalMapping(area = "custom", domain = "custom")
public class CustomResource {
    // Not a BaseResource, so needs explicit annotation
}
```

### Multiple Resources for Same Model

All automatically use the same model mapping:

```java
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

@Path("/locations")
public class LocationResource extends BaseResource<Location, LocationRepo> { }

@Path("/api/v1/locations")
public class LocationResourceV1 extends BaseResource<Location, LocationRepo> { }

@Path("/api/v2/locations")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> { }

// All three use: area="people_hub", domain="location"
```

## Summary

**Before**: Had to annotate both model AND REST resource (duplication, sync issues)

**After**: Annotate model ONLY, REST resources work automatically (single source of truth)

This is the minimal, correct solution that eliminates duplication while maintaining flexibility for edge cases.
