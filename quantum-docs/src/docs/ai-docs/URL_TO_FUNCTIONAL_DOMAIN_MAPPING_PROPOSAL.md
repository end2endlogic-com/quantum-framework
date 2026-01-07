# URL to Functional Domain Mapping Proposal

## Problem Statement

There's currently a mismatch between:
1. **JAX-RS URL paths** (e.g., `/people_hub/location/list`) parsed by SecurityFilter
2. **Model annotations** (`@FunctionalMapping(area="...", domain="...")`) on entity classes

This causes authorization issues because:
- SecurityFilter extracts area/domain/action from URL segments
- Model classes define their functional area/domain via annotations
- REST resources may use different URL patterns than the model's functional mapping
- No automatic linkage exists between the two

## Current State

### Model Annotation
```java
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel {
    // ...
}
```

### REST Resource (Implicit)
```java
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    // Inherits CRUD endpoints: /people_hub/location/list, /people_hub/location/id/{id}, etc.
}
```

### SecurityFilter Parsing
```java
// For URL: /people_hub/location/list
// Extracts: area="people_hub", domain="location", action="list"
```

## Proposed Solutions

### Solution 1: Annotate REST Resources (Recommended)

Add `@FunctionalMapping` to REST resource classes to explicitly declare their functional area/domain.

#### Implementation

**Step 1**: Make `@FunctionalMapping` applicable to both TYPE (classes) and METHOD (endpoints)

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface FunctionalMapping {
    String area();
    String domain();
}
```

**Step 2**: Annotate REST resources

```java
@Path("/people_hub/location")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    public LocationResource(LocationRepo repo) {
        super(repo);
    }
}
```

**Step 3**: Update SecurityFilter to prefer annotation over URL parsing

```java
// In SecurityFilter.determineResourceContext()
if (resourceInfo != null && resourceInfo.getResourceClass() != null) {
    Class<?> rc = resourceInfo.getResourceClass();
    FunctionalMapping fm = rc.getAnnotation(FunctionalMapping.class);
    
    if (fm != null) {
        String area = fm.area();
        String domain = fm.domain();
        String action = inferActionFromHttpMethod(requestContext.getMethod());
        
        // Check for method-level override
        if (resourceInfo.getResourceMethod() != null) {
            FunctionalAction fa = resourceInfo.getResourceMethod()
                .getAnnotation(FunctionalAction.class);
            if (fa != null) {
                action = fa.value();
            }
        }
        
        return new ResourceContext.Builder()
            .withArea(area)
            .withFunctionalDomain(domain)
            .withAction(action)
            .build();
    }
}
// Fall back to URL parsing if no annotation
```

**Pros**:
- Explicit and clear
- Decouples URL structure from functional mapping
- Allows URL refactoring without breaking authorization
- Works with existing annotation infrastructure

**Cons**:
- Requires annotating all REST resources
- Duplication if model and resource have same mapping

---

### Solution 2: Auto-Derive from Model Class

Automatically infer functional area/domain from the model class that the REST resource operates on.

#### Implementation

**Step 1**: Add model class introspection to BaseResource

```java
public abstract class BaseResource<T extends UnversionedBaseModel, R extends BaseMorphiaRepo<T>> {
    
    protected Class<T> modelClass;
    
    protected BaseResource(R repo) {
        this.repo = repo;
        // Extract model class from generic type
        this.modelClass = extractModelClass();
    }
    
    @SuppressWarnings("unchecked")
    private Class<T> extractModelClass() {
        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) genericSuperclass;
            return (Class<T>) pt.getActualTypeArguments()[0];
        }
        return null;
    }
    
    public FunctionalMapping getModelFunctionalMapping() {
        if (modelClass != null) {
            return modelClass.getAnnotation(FunctionalMapping.class);
        }
        return null;
    }
}
```

**Step 2**: Create a JAX-RS ContainerRequestFilter to inject model mapping

```java
@Provider
@Priority(Priorities.AUTHORIZATION - 100) // Run before SecurityFilter
public class ModelMappingInjectionFilter implements ContainerRequestFilter {
    
    @Context
    ResourceInfo resourceInfo;
    
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (resourceInfo.getResourceClass() != null) {
            Object resource = getResourceInstance();
            if (resource instanceof BaseResource) {
                BaseResource<?, ?> baseResource = (BaseResource<?, ?>) resource;
                FunctionalMapping mapping = baseResource.getModelFunctionalMapping();
                if (mapping != null) {
                    // Store in request context for SecurityFilter to use
                    requestContext.setProperty("model.functional.area", mapping.area());
                    requestContext.setProperty("model.functional.domain", mapping.domain());
                }
            }
        }
    }
}
```

**Step 3**: Update SecurityFilter to check request properties

```java
// In SecurityFilter.determineResourceContext()
// First check for injected model mapping
String modelArea = (String) requestContext.getProperty("model.functional.area");
String modelDomain = (String) requestContext.getProperty("model.functional.domain");

if (modelArea != null && modelDomain != null) {
    String action = inferActionFromHttpMethod(requestContext.getMethod());
    return new ResourceContext.Builder()
        .withArea(modelArea)
        .withFunctionalDomain(modelDomain)
        .withAction(action)
        .build();
}
// Fall back to annotation or URL parsing
```

**Pros**:
- No need to annotate REST resources
- Single source of truth (model annotation)
- Automatic for all BaseResource subclasses

**Cons**:
- More complex implementation
- Assumes REST resource always maps 1:1 with model
- Harder to override for special cases

---

### Solution 3: Hybrid Approach (Best of Both)

Combine both solutions with a priority order:
1. REST resource method annotation (highest priority)
2. REST resource class annotation
3. Model class annotation (auto-derived)
4. URL path parsing (fallback)

#### Implementation

```java
// In SecurityFilter.determineResourceContext()
public ResourceContext determineResourceContext(ContainerRequestContext requestContext) {
    String area = null;
    String domain = null;
    String action = null;
    
    // Priority 1: Method-level annotation
    if (resourceInfo != null && resourceInfo.getResourceMethod() != null) {
        FunctionalMapping methodMapping = resourceInfo.getResourceMethod()
            .getAnnotation(FunctionalMapping.class);
        if (methodMapping != null) {
            area = methodMapping.area();
            domain = methodMapping.domain();
        }
        
        FunctionalAction methodAction = resourceInfo.getResourceMethod()
            .getAnnotation(FunctionalAction.class);
        if (methodAction != null) {
            action = methodAction.value();
        }
    }
    
    // Priority 2: Class-level annotation
    if (area == null && resourceInfo != null && resourceInfo.getResourceClass() != null) {
        FunctionalMapping classMapping = resourceInfo.getResourceClass()
            .getAnnotation(FunctionalMapping.class);
        if (classMapping != null) {
            area = classMapping.area();
            domain = classMapping.domain();
        }
    }
    
    // Priority 3: Model class annotation (from request property)
    if (area == null) {
        area = (String) requestContext.getProperty("model.functional.area");
        domain = (String) requestContext.getProperty("model.functional.domain");
    }
    
    // Priority 4: URL path parsing (existing logic)
    if (area == null) {
        return parseFromUrlPath(requestContext);
    }
    
    // Infer action if not explicitly set
    if (action == null) {
        action = inferActionFromHttpMethod(requestContext.getMethod());
        // Apply normalization (list -> LIST)
        if ("list".equalsIgnoreCase(action)) {
            action = "LIST";
        }
    }
    
    return new ResourceContext.Builder()
        .withArea(area)
        .withFunctionalDomain(domain)
        .withAction(action)
        .build();
}
```

**Pros**:
- Maximum flexibility
- Backward compatible (URL parsing still works)
- Allows fine-grained control when needed
- Automatic for simple cases

**Cons**:
- Most complex to implement
- Multiple ways to achieve same result (could be confusing)

---

## Recommended Approach

**Use Solution 3 (Hybrid)** with the following guidelines:

### For New Code
1. Annotate model classes with `@FunctionalMapping`
2. Let BaseResource auto-derive from model (no annotation needed on resource)
3. Only annotate REST resource if it differs from model mapping

### For Existing Code
1. Keep URL parsing as fallback
2. Gradually add annotations to models
3. Add resource annotations only where URL doesn't match model

### Migration Path

**Phase 1**: Implement auto-derivation from models
```java
// Models already have annotations
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

// Resources work automatically
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> { }
```

**Phase 2**: Add resource annotations where URLs differ
```java
// URL doesn't match model's functional mapping
@Path("/api/v2/locations")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> { }
```

**Phase 3**: Add method-level overrides for special endpoints
```java
@Path("/people_hub/location")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    
    @GET
    @Path("/nearby")
    @FunctionalAction("SEARCH_NEARBY")
    public Response findNearby(@QueryParam("lat") double lat, 
                               @QueryParam("lon") double lon) {
        // Custom action for this specific endpoint
    }
}
```

---

## Implementation Files

### Files to Modify

1. **FunctionalMapping.java**
   - Add `ElementType.METHOD` to `@Target`

2. **SecurityFilter.java**
   - Add hybrid resolution logic in `determineResourceContext()`
   - Implement priority-based lookup

3. **BaseResource.java**
   - Add `modelClass` field and extraction logic
   - Add `getModelFunctionalMapping()` method

4. **New: ModelMappingInjectionFilter.java**
   - Create filter to inject model mapping into request context
   - Run before SecurityFilter

### Testing Strategy

1. **Unit Tests**
   - Test each priority level independently
   - Test fallback chain
   - Test action normalization

2. **Integration Tests**
   - Test with annotated resources
   - Test with non-annotated resources (URL parsing)
   - Test with mixed scenarios

3. **Migration Tests**
   - Verify existing URLs still work
   - Verify new annotations take precedence
   - Verify backward compatibility

---

## Example Usage

### Simple Case (Auto-derived)
```java
// Model
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

// Resource (no annotation needed)
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> { }

// Authorization check will use: area="people_hub", domain="location"
```

### Override Case
```java
// Model
@FunctionalMapping(area = "people_hub", domain = "location")
public class Location extends BaseModel { }

// Resource with different URL structure
@Path("/api/locations")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationApiResource extends BaseResource<Location, LocationRepo> { }

// Authorization check will use annotation, not URL
```

### Custom Action Case
```java
@Path("/people_hub/location")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    
    @POST
    @Path("/bulk-import")
    @FunctionalAction("BULK_IMPORT")
    public Response bulkImport(List<Location> locations) {
        // Custom action: area="people_hub", domain="location", action="BULK_IMPORT"
    }
}
```

---

## Benefits

1. **Consistency**: Single source of truth for functional mappings
2. **Flexibility**: Multiple ways to specify mapping based on needs
3. **Backward Compatible**: Existing URL-based parsing still works
4. **Maintainability**: Changes to URLs don't break authorization
5. **Clarity**: Explicit annotations make intent clear
6. **Automation**: Auto-derivation reduces boilerplate

## Next Steps

1. Review and approve approach
2. Implement Phase 1 (auto-derivation)
3. Update documentation
4. Create migration guide
5. Add unit tests
6. Roll out to existing resources incrementally
