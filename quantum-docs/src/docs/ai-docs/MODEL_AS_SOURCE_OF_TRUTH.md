# Model as Single Source of Truth - Implementation

## Problem
Having `@FunctionalMapping` on both model classes and REST resources creates duplication and sync issues.

## Solution
Use the model class annotation as the single source of truth. Extract it from the generic type parameter in `BaseResource<T, R>`.

## Implementation

### Step 1: Add Model Class Extraction to BaseResource

```java
public abstract class BaseResource<T extends UnversionedBaseModel, R extends BaseMorphiaRepo<T>> {
    protected R repo;
    protected Class<T> modelClass;
    
    @Inject
    protected JsonWebToken jwt;
    
    @Inject
    protected RuleContext ruleContext;
    
    protected BaseResource(R repo) {
        this.repo = repo;
        this.modelClass = extractModelClass();
    }
    
    @SuppressWarnings("unchecked")
    private Class<T> extractModelClass() {
        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) genericSuperclass;
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                return (Class<T>) typeArgs[0];
            }
        }
        return null;
    }
    
    /**
     * Get the functional mapping from the model class.
     * This is the single source of truth for area/domain.
     */
    public FunctionalMapping getModelFunctionalMapping() {
        if (modelClass != null) {
            return modelClass.getAnnotation(FunctionalMapping.class);
        }
        return null;
    }
}
```

### Step 2: Create Filter to Inject Model Mapping into Request

```java
package com.e2eq.framework.rest.filters;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.rest.resources.BaseResource;
import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * Extracts functional mapping from the model class and injects it into the request context
 * for SecurityFilter to use. This makes the model class the single source of truth.
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 100) // Run before SecurityFilter
public class ModelMappingExtractor implements ContainerRequestFilter {
    
    @Context
    ResourceInfo resourceInfo;
    
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return;
        }
        
        try {
            Class<?> resourceClass = resourceInfo.getResourceClass();
            
            // Check if this is a BaseResource subclass
            if (BaseResource.class.isAssignableFrom(resourceClass)) {
                // Try to get an instance to call getModelFunctionalMapping()
                // In CDI context, we can't easily get the instance, so we extract directly
                FunctionalMapping mapping = extractMappingFromResourceClass(resourceClass);
                
                if (mapping != null) {
                    requestContext.setProperty("model.functional.area", mapping.area());
                    requestContext.setProperty("model.functional.domain", mapping.domain());
                    
                    if (Log.isDebugEnabled()) {
                        Log.debugf("Extracted model mapping: area=%s, domain=%s from %s", 
                            mapping.area(), mapping.domain(), resourceClass.getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Failed to extract model mapping: %s", e.getMessage());
            }
        }
    }
    
    /**
     * Extract FunctionalMapping from the model class by inspecting the generic type parameter
     */
    private FunctionalMapping extractMappingFromResourceClass(Class<?> resourceClass) {
        try {
            java.lang.reflect.Type genericSuperclass = resourceClass.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) genericSuperclass;
                java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                
                if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                    Class<?> modelClass = (Class<?>) typeArgs[0];
                    return modelClass.getAnnotation(FunctionalMapping.class);
                }
            }
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Error extracting model class: %s", e.getMessage());
            }
        }
        return null;
    }
}
```

### Step 3: Update SecurityFilter Priority

```java
// In SecurityFilter.determineResourceContext()
// Priority 1: Method-level annotation (for special cases only)
// Priority 2: Model class annotation (from request property) - SINGLE SOURCE OF TRUTH
// Priority 3: Class-level annotation (for non-BaseResource classes)
// Priority 4: URL path parsing (fallback)

String area = null;
String functionalDomain = null;
String action = null;

try {
    if (resourceInfo != null) {
        // Priority 1: Method-level annotation (rare overrides)
        java.lang.reflect.Method rm = resourceInfo.getResourceMethod();
        if (rm != null) {
            com.e2eq.framework.annotations.FunctionalMapping methodMapping = 
                rm.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
            if (methodMapping != null) {
                area = methodMapping.area();
                functionalDomain = methodMapping.domain();
                if (Log.isDebugEnabled()) {
                    Log.debugf("Using method-level @FunctionalMapping: area=%s, domain=%s", area, functionalDomain);
                }
            }
            
            com.e2eq.framework.annotations.FunctionalAction fa = 
                rm.getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class);
            if (fa != null) {
                action = fa.value();
            }
        }
        
        // Priority 2: Model class annotation (SINGLE SOURCE OF TRUTH)
        if (area == null) {
            String modelArea = (String) requestContext.getProperty("model.functional.area");
            String modelDomain = (String) requestContext.getProperty("model.functional.domain");
            if (modelArea != null && modelDomain != null) {
                area = modelArea;
                functionalDomain = modelDomain;
                if (Log.isDebugEnabled()) {
                    Log.debugf("Using model class @FunctionalMapping: area=%s, domain=%s", area, functionalDomain);
                }
            }
        }
        
        // Priority 3: Class-level annotation (for non-BaseResource classes)
        if (area == null && resourceInfo.getResourceClass() != null) {
            Class<?> rc = resourceInfo.getResourceClass();
            com.e2eq.framework.annotations.FunctionalMapping classMapping = 
                rc.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
            if (classMapping != null) {
                area = classMapping.area();
                functionalDomain = classMapping.domain();
                if (Log.isDebugEnabled()) {
                    Log.debugf("Using class-level @FunctionalMapping: area=%s, domain=%s", area, functionalDomain);
                }
            }
        }
        
        // If we found area/domain, build context and return
        if (area != null && functionalDomain != null) {
            if (action == null) {
                action = inferActionFromHttpMethod(requestContext.getMethod());
            }
            if ("list".equalsIgnoreCase(action)) {
                action = "LIST";
            }
            rcontext = new ResourceContext.Builder()
                    .withArea(area)
                    .withFunctionalDomain(functionalDomain)
                    .withAction(action)
                    .build();
            SecurityContext.setResourceContext(rcontext);
            if (Log.isDebugEnabled()) {
                Log.debugf("Resource Context set: area=%s, domain=%s, action=%s", 
                    area, functionalDomain, action);
            }
            return rcontext;
        }
    }
} catch (Exception ex) {
    Log.debugf("Annotation-based resource context resolution failed: %s", ex.toString());
}

// Priority 4: Fall back to URL parsing
```

## Usage

### Standard Case (Automatic)
```java
// Model - SINGLE SOURCE OF TRUTH
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel {
    // ...
}

// Resource - NO ANNOTATION NEEDED
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    public LocationResource(LocationRepo repo) {
        super(repo);
    }
    // Automatically uses Location's @FunctionalMapping
}

// Even with different URL
@Path("/api/v2/locations")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> {
    public LocationResourceV2(LocationRepo repo) {
        super(repo);
    }
    // Still uses Location's @FunctionalMapping - URL doesn't matter!
}
```

### Override for Special Endpoints (Rare)
```java
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    
    @GET
    @Path("/nearby")
    @FunctionalAction("SEARCH_NEARBY")
    public Response findNearby(@QueryParam("lat") double lat, @QueryParam("lon") double lon) {
        // Uses Location's area/domain but custom action
    }
    
    @POST
    @Path("/admin/bulk-delete")
    @FunctionalMapping(area = "admin", domain = "location_admin")
    @FunctionalAction("BULK_DELETE")
    public Response bulkDelete(List<String> ids) {
        // Rare case: completely different area/domain for admin operation
    }
}
```

## Benefits

✅ **Single Source of Truth**: Model class annotation is the only place to define area/domain

✅ **No Duplication**: REST resources don't need annotations (unless overriding)

✅ **Automatic**: Works for all BaseResource subclasses without any code changes

✅ **URL Independent**: Can change URLs without affecting authorization

✅ **Backward Compatible**: URL parsing still works as fallback

✅ **Override When Needed**: Method-level annotations for special cases

## Migration

### Existing Code
No changes needed! Model classes already have `@FunctionalMapping`:

```java
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> { }
// Works automatically!
```

### Remove Duplicate Annotations
If you added REST resource annotations, you can remove them:

```java
// Before
@Path("/api/locations")
@FunctionalMapping(area = "people_hub", domain = "location") // REMOVE THIS
public class LocationResource extends BaseResource<Location, LocationRepo> { }

// After
@Path("/api/locations")
public class LocationResource extends BaseResource<Location, LocationRepo> { }
// Uses Location model's annotation automatically
```

## Testing

```bash
# Test that model annotation is used regardless of URL
curl -X POST http://localhost:8080/system/permissions/check \
  -H "Content-Type: application/json" \
  -d '{
    "identity": "user@example.com",
    "area": "people_hub",
    "functionalDomain": "location",
    "action": "LIST"
  }'

# Should work for both:
# GET /people_hub/location/list
# GET /api/v2/locations/list
# GET /any/url/structure/list
```
