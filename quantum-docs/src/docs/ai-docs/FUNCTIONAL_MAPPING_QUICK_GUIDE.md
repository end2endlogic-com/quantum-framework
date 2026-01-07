# Functional Mapping Quick Reference Guide

## Overview
The framework now supports multiple ways to map REST endpoints to functional areas and domains for authorization. The system uses a **priority-based resolution** to determine the correct mapping.

## Resolution Priority

1. **Method-level annotation** (highest priority)
2. **Class-level annotation** on REST resource
3. **URL path parsing** (fallback)

## Usage Examples

### Example 1: Simple Case (URL Parsing - Backward Compatible)

No annotations needed. The system parses the URL path.

```java
@Path("/people_hub/location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    // URL: /people_hub/location/list
    // Resolves to: area="people_hub", domain="location", action="LIST"
}
```

### Example 2: Class-Level Annotation (Recommended)

Explicitly declare the functional mapping on the REST resource class.

```java
@Path("/api/v2/locations")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> {
    // URL: /api/v2/locations/list
    // Resolves to: area="people_hub", domain="location", action="LIST"
    // (Uses annotation, not URL parsing)
}
```

### Example 3: Method-Level Override

Override the mapping for specific endpoints.

```java
@Path("/people_hub/location")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    
    // Standard endpoint - uses class-level mapping
    // GET /people_hub/location/list
    // area="people_hub", domain="location", action="LIST"
    
    @GET
    @Path("/nearby")
    @FunctionalMapping(area = "people_hub", domain = "location_search")
    @FunctionalAction("SEARCH_NEARBY")
    public Response findNearby(@QueryParam("lat") double lat, 
                               @QueryParam("lon") double lon) {
        // Custom mapping for this specific endpoint
        // GET /people_hub/location/nearby
        // area="people_hub", domain="location_search", action="SEARCH_NEARBY"
    }
}
```

### Example 4: Custom Action Only

Keep area/domain from class but override action.

```java
@Path("/people_hub/location")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResource extends BaseResource<Location, LocationRepo> {
    
    @POST
    @Path("/bulk-import")
    @FunctionalAction("BULK_IMPORT")
    public Response bulkImport(List<Location> locations) {
        // area="people_hub", domain="location", action="BULK_IMPORT"
    }
}
```

## When to Use Each Approach

### Use URL Parsing (No Annotation)
- Quick prototyping
- URL structure matches functional mapping
- Backward compatibility with existing code

**Example**: `/people_hub/location/list` naturally maps to area="people_hub", domain="location"

### Use Class-Level Annotation
- URL structure doesn't match functional mapping
- Versioned APIs (e.g., `/api/v2/...`)
- Want explicit declaration for clarity
- Multiple resources for same model

**Example**: `/api/locations` should map to area="people_hub", domain="location"

### Use Method-Level Annotation
- Special endpoints with different authorization requirements
- Custom actions not covered by CRUD
- Endpoints that operate on multiple domains

**Example**: `/location/nearby` needs different domain or action than standard CRUD

## Action Normalization

The system automatically normalizes common actions:

| HTTP Method | URL Segment | Normalized Action |
|-------------|-------------|-------------------|
| GET         | `/list`     | `LIST`            |
| GET         | `/id/{id}`  | `VIEW`            |
| POST        | `/`         | `CREATE`          |
| PUT         | `/id/{id}`  | `UPDATE`          |
| DELETE      | `/id/{id}`  | `DELETE`          |

You can override with `@FunctionalAction("CUSTOM_ACTION")`.

## Migration Guide

### Step 1: Identify Mismatches
Find resources where URL doesn't match intended functional mapping:

```bash
# Look for resources with non-standard URL patterns
grep -r "@Path" --include="*Resource.java" | grep -v "people_hub\|security\|system"
```

### Step 2: Add Class-Level Annotations
For resources with mismatched URLs:

```java
// Before
@Path("/api/v2/locations")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> { }

// After
@Path("/api/v2/locations")
@FunctionalMapping(area = "people_hub", domain = "location")
public class LocationResourceV2 extends BaseResource<Location, LocationRepo> { }
```

### Step 3: Add Method-Level Annotations for Special Cases
For custom endpoints:

```java
@POST
@Path("/special-operation")
@FunctionalAction("SPECIAL_OPERATION")
public Response specialOperation() { }
```

### Step 4: Test Authorization
Use the check API to verify:

```bash
curl -X POST http://localhost:8080/system/permissions/check \
  -H "Content-Type: application/json" \
  -d '{
    "identity": "user@example.com",
    "area": "people_hub",
    "functionalDomain": "location",
    "action": "LIST"
  }'
```

## Debugging

### Enable Debug Logging
```properties
quarkus.log.category."com.e2eq.framework.rest.filters.SecurityFilter".level=DEBUG
```

### Check What Was Resolved
Look for log messages like:
```
Using method-level @FunctionalMapping: area=people_hub, domain=location
Using class-level @FunctionalMapping: area=people_hub, domain=location
Resource Context set from path (3 segments): area=people_hub, domain=location, action=LIST
```

### Common Issues

**Issue**: Authorization fails even though check API returns ALLOW

**Solution**: Check if action is being normalized correctly. Use uppercase in check API:
```json
{
  "action": "LIST"  // Not "list"
}
```

**Issue**: Custom endpoint gets wrong functional mapping

**Solution**: Add method-level `@FunctionalMapping` or `@FunctionalAction`:
```java
@GET
@Path("/custom")
@FunctionalAction("CUSTOM_ACTION")
public Response custom() { }
```

## Best Practices

1. **Be Explicit**: When in doubt, add annotations rather than relying on URL parsing
2. **Document Custom Actions**: Add comments explaining why custom actions are needed
3. **Test Both Ways**: Test with check API and actual endpoint calls
4. **Use Consistent Naming**: Keep area/domain names consistent across models and resources
5. **Version Carefully**: Use class-level annotations for versioned APIs

## Examples from Framework

### SecurityResource
```java
@Path("/security")
// No annotation - uses URL parsing
// area="security", domain inferred from endpoints
```

### PolicyResource
```java
@Path("/security/permission/policies")
// Complex path - should add annotation:
@FunctionalMapping(area = "security", domain = "policy")
```

### BaseResource
```java
// Generic base class - subclasses can add annotations
@Path("/custom/path")
@FunctionalMapping(area = "my_area", domain = "my_domain")
public class MyResource extends BaseResource<MyModel, MyRepo> { }
```

## Summary

- **Annotations take precedence** over URL parsing
- **Method-level** beats **class-level** beats **URL parsing**
- **Backward compatible**: Existing code without annotations still works
- **Flexible**: Choose the right approach for your use case
- **Explicit is better**: When in doubt, add annotations for clarity
