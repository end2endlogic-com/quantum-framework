# Security, Provenance, Updates & Hierarchy Enhancement Plan

## Overview
This document outlines the implementation plan for security/provenance improvements, update enhancements, validation features, and hierarchy optimizations in the Quantum framework.

## 1. Security & Provenance

### 1.1 Centralized modelSourceRealm Setting

**Requirement**: Set `modelSourceRealm` on all read paths (findById, findByRefName, list/stream queries).

**Implementation**:
```java
// MorphiaRepo.java - Add helper method
protected <T extends UnversionedBaseModel> T setSourceRealm(T entity, String realmId) {
    if (entity != null) {
        entity.setModelSourceRealm(realmId);
    }
    return entity;
}

protected <T extends UnversionedBaseModel> List<T> setSourceRealm(List<T> entities, String realmId) {
    if (entities != null) {
        entities.forEach(e -> e.setModelSourceRealm(realmId));
    }
    return entities;
}

protected <T extends UnversionedBaseModel> Stream<T> setSourceRealm(Stream<T> stream, String realmId) {
    return stream.map(e -> setSourceRealm(e, realmId));
}
```

**Refactor all read methods**:
- `findById` → `return setSourceRealm(ds.find(...).first(), realmId);`
- `findByRefName` → `return setSourceRealm(result, realmId);`
- `list` → `return setSourceRealm(results, realmId);`
- `stream` → `return setSourceRealm(stream, realmId);`

**Test**:
```java
@Test
void testSourceRealmSetOnRead() {
    MyEntity entity = repo.findById(realmId, id);
    assertNotNull(entity);
    assertEquals(realmId, entity.getModelSourceRealm());
}
```

### 1.2 DTOs for Read Endpoints

**Requirement**: Return DTOs that include sourceRealm explicitly to avoid accidental persistence.

**Implementation**:
```java
// Base DTO
public class EntityDTO<T extends UnversionedBaseModel> {
    private T entity;
    private String sourceRealm;
    private Date readAt;
    
    public static <T extends UnversionedBaseModel> EntityDTO<T> from(T entity, String realm) {
        return new EntityDTO<>(entity, realm, new Date());
    }
}

// Resource layer
@GET
@Path("/{id}")
public Response getById(@PathParam("id") String id) {
    MyEntity entity = repo.findById(realmId, id);
    return Response.ok(EntityDTO.from(entity, realmId)).build();
}
```

### 1.3 Anonymous Fallback in ensureSecurityContextFromIdentity

**Requirement**: Add config to allow anonymous fallback instead of throwing when no credentials found.

**Implementation**:
```java
// application.properties
quantum.security.allow-anonymous-fallback=true

// MorphiaRepo.java
@ConfigProperty(name = "quantum.security.allow-anonymous-fallback", defaultValue = "false")
boolean allowAnonymousFallback;

protected SecurityContext ensureSecurityContextFromIdentity(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
        if (allowAnonymousFallback) {
            log.warn("No security credentials found, proceeding with anonymous scope");
            return SecurityContext.anonymous();
        } else {
            throw new SecurityException("Authentication required");
        }
    }
    return SecurityContext.from(identity);
}
```

## 2. Updates and Validation

### 2.1 State Transition Validation in updateMany

**Requirement**: Document that state transitions aren't validated per-entity, or implement optional validation.

**Implementation**:
```java
// MorphiaRepo.java
/**
 * Bulk update without per-entity state transition validation.
 * For state-aware updates, use updateManyWithValidation.
 */
public long updateMany(Datastore ds, Filter filter, UpdateOperator... updates) {
    // Current implementation - no validation
}

/**
 * Bulk update with optional state transition validation.
 * Groups entities by current state and validates transitions.
 */
public UpdateManyResult updateManyWithValidation(Datastore ds, Filter filter, 
                                                  String targetState, 
                                                  StateTransitionValidator validator) {
    // 1. Find all matching entities
    List<T> entities = ds.find(entityClass).filter(filter).iterator().toList();
    
    // 2. Group by current state
    Map<String, List<T>> byState = entities.stream()
        .collect(Collectors.groupingBy(e -> e.getActiveStatus().getState()));
    
    // 3. Validate transitions for each group
    Map<String, List<String>> errors = new HashMap<>();
    List<ObjectId> validIds = new ArrayList<>();
    
    for (Map.Entry<String, List<T>> entry : byState.entrySet()) {
        String currentState = entry.getKey();
        if (validator.isValidTransition(currentState, targetState)) {
            entry.getValue().forEach(e -> validIds.add(e.getId()));
        } else {
            errors.put(currentState, List.of("Invalid transition: " + currentState + " -> " + targetState));
        }
    }
    
    // 4. Update only valid entities
    long updated = 0;
    if (!validIds.isEmpty()) {
        updated = ds.find(entityClass)
            .filter(Filters.in("_id", validIds))
            .update(UpdateOperators.set("activeStatus.state", targetState))
            .execute()
            .getModifiedCount();
    }
    
    return new UpdateManyResult(updated, errors);
}

public record UpdateManyResult(long updated, Map<String, List<String>> errors) {}
```

### 2.2 Optimistic Locking

**Requirement**: Accept optional version in updates, include in filter, return 409 on mismatch.

**Implementation**:
```java
// Add version field to UnversionedBaseModel
@Version
protected Long version;

// MorphiaRepo.java
public OptimisticUpdateResult updateWithVersion(Datastore ds, ObjectId id, Long expectedVersion, 
                                                 UpdateOperator... updates) {
    Filter filter = Filters.and(
        Filters.eq("_id", id),
        expectedVersion != null ? Filters.eq("version", expectedVersion) : Filters.exists("_id")
    );
    
    UpdateResult result = ds.find(entityClass)
        .filter(filter)
        .update(updates)
        .execute();
    
    if (result.getModifiedCount() == 0) {
        // Check if entity exists
        T entity = ds.find(entityClass).filter(Filters.eq("_id", id)).first();
        if (entity == null) {
            return OptimisticUpdateResult.notFound();
        } else {
            return OptimisticUpdateResult.versionMismatch(entity.getVersion());
        }
    }
    
    return OptimisticUpdateResult.success(result.getModifiedCount());
}

public sealed interface OptimisticUpdateResult {
    record Success(long modified) implements OptimisticUpdateResult {}
    record VersionMismatch(Long currentVersion) implements OptimisticUpdateResult {}
    record NotFound() implements OptimisticUpdateResult {}
    
    static OptimisticUpdateResult success(long count) { return new Success(count); }
    static OptimisticUpdateResult versionMismatch(Long version) { return new VersionMismatch(version); }
    static OptimisticUpdateResult notFound() { return new NotFound(); }
}

// Resource layer
@PUT
@Path("/{id}")
public Response update(@PathParam("id") String id, 
                      @HeaderParam("If-Match") Long version,
                      MyEntity updates) {
    var result = repo.updateWithVersion(ds, new ObjectId(id), version, ...);
    return switch (result) {
        case Success(long count) -> Response.ok().build();
        case VersionMismatch(Long current) -> Response.status(409)
            .entity(Map.of("error", "Version mismatch", "currentVersion", current))
            .build();
        case NotFound() -> Response.status(404).build();
    };
}
```

### 2.3 Type Coercion for Update Payloads

**Requirement**: Add simple type coercion (int→long, string→enum) with strict logging.

**Implementation**:
```java
// TypeCoercer.java
public class TypeCoercer {
    private static final Logger log = Logger.getLogger(TypeCoercer.class);
    
    public static Object coerce(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        
        try {
            // int → long
            if (targetType == Long.class && value instanceof Integer) {
                log.warnf("Coercing Integer %s to Long", value);
                return ((Integer) value).longValue();
            }
            
            // String → Enum
            if (targetType.isEnum() && value instanceof String) {
                log.warnf("Coercing String '%s' to enum %s", value, targetType.getSimpleName());
                return Enum.valueOf((Class<Enum>) targetType, (String) value);
            }
            
            // String → Integer
            if (targetType == Integer.class && value instanceof String) {
                log.warnf("Coercing String '%s' to Integer", value);
                return Integer.parseInt((String) value);
            }
            
            // String → Long
            if (targetType == Long.class && value instanceof String) {
                log.warnf("Coercing String '%s' to Long", value);
                return Long.parseLong((String) value);
            }
            
            log.warnf("No coercion available from %s to %s", value.getClass(), targetType);
            return value;
        } catch (Exception e) {
            log.errorf(e, "Failed to coerce %s to %s", value, targetType);
            throw new IllegalArgumentException("Type coercion failed: " + e.getMessage());
        }
    }
}

// MorphiaRepo.java - Apply in update methods
protected Map<String, Object> coerceUpdatePayload(Map<String, Object> payload, Class<T> entityClass) {
    Map<String, Object> coerced = new HashMap<>();
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
        try {
            Field field = entityClass.getDeclaredField(entry.getKey());
            Object coercedValue = TypeCoercer.coerce(entry.getValue(), field.getType());
            coerced.put(entry.getKey(), coercedValue);
        } catch (NoSuchFieldException e) {
            coerced.put(entry.getKey(), entry.getValue());
        }
    }
    return coerced;
}
```

## 3. Hierarchy & Menu Optimization

### 3.1 Clarify descendants Field

**Requirement**: Rename to `childrenIds` if direct children, or add helpers if transitive.

**Decision**: Assume `descendants` is direct children → rename to `childrenIds`.

**Implementation**:
```java
// HierarchicalModel.java
@Deprecated(forRemoval = true)
protected List<ObjectId> descendants; // Old field

protected List<ObjectId> childrenIds; // New field for direct children

// Migration changeset
public class RenameDescendantsToChildrenIds implements ChangeSet {
    public void execute(Datastore ds) {
        ds.getDatabase().getCollection("menu_items")
            .updateMany(new Document(), 
                new Document("$rename", new Document("descendants", "childrenIds")));
    }
}
```

### 3.2 Transactional Child Add/Remove

**Requirement**: Add/remove child updating both parent.childrenIds and child.parent atomically.

**Implementation**:
```java
// HierarchicalRepo.java
@Transactional
public void addChild(String realmId, ObjectId parentId, ObjectId childId) {
    Datastore ds = getDatastore(realmId);
    
    // Update parent: add to childrenIds
    ds.find(entityClass)
        .filter(Filters.eq("_id", parentId))
        .update(UpdateOperators.addToSet("childrenIds", childId))
        .execute();
    
    // Update child: set parent
    ds.find(entityClass)
        .filter(Filters.eq("_id", childId))
        .update(UpdateOperators.set("parent", parentId))
        .execute();
}

@Transactional
public void removeChild(String realmId, ObjectId parentId, ObjectId childId) {
    Datastore ds = getDatastore(realmId);
    
    // Update parent: remove from childrenIds
    ds.find(entityClass)
        .filter(Filters.eq("_id", parentId))
        .update(UpdateOperators.pull("childrenIds", childId))
        .execute();
    
    // Update child: clear parent
    ds.find(entityClass)
        .filter(Filters.eq("_id", childId))
        .update(UpdateOperators.unset("parent"))
        .execute();
}
```

### 3.3 Optimize MenuHierarchyRepo.getFilteredMenu

**Requirement**: Replace recursive per-node DB calls with batched subtree fetch.

**Implementation**:
```java
// MenuHierarchyRepo.java
public MenuItemDTO getFilteredMenu(String realmId, ObjectId rootId, Set<String> identities) {
    // 1. Fetch entire subtree in one query using $graphLookup or recursive CTE
    List<MenuItem> allNodes = fetchSubtree(realmId, rootId);
    
    // 2. Build in-memory tree
    Map<ObjectId, MenuItem> nodeMap = allNodes.stream()
        .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
    
    // 3. Filter by permissions
    List<MenuItem> filtered = allNodes.stream()
        .filter(node -> isAccessible(node, identities))
        .collect(Collectors.toList());
    
    // 4. Build DTO tree (prevents persisting filtered clones)
    return buildDTOTree(filtered, nodeMap, rootId);
}

private List<MenuItem> fetchSubtree(String realmId, ObjectId rootId) {
    // Use MongoDB $graphLookup for efficient subtree fetch
    Datastore ds = getDatastore(realmId);
    return ds.getDatabase()
        .getCollection("menu_items", MenuItem.class)
        .aggregate(List.of(
            Aggregates.match(Filters.eq("_id", rootId)),
            Aggregates.graphLookup("menu_items", "$_id", "parent", "_id", "subtree")
        ))
        .into(new ArrayList<>());
}

private boolean isAccessible(MenuItem node, Set<String> identities) {
    if (identities == null || identities.isEmpty()) {
        // Behavior for null/empty identities: deny by default
        return node.getAllowedIdentities() == null || node.getAllowedIdentities().isEmpty();
    }
    
    if (node.getAllowedIdentities() == null || node.getAllowedIdentities().isEmpty()) {
        // Empty allowedIdentities = public access
        return true;
    }
    
    // Check if any identity matches
    return node.getAllowedIdentities().stream()
        .anyMatch(identities::contains);
}

private MenuItemDTO buildDTOTree(List<MenuItem> nodes, Map<ObjectId, MenuItem> allNodes, ObjectId rootId) {
    MenuItem root = allNodes.get(rootId);
    if (root == null) return null;
    
    List<MenuItemDTO> children = nodes.stream()
        .filter(n -> rootId.equals(n.getParent()))
        .map(n -> buildDTOTree(nodes, allNodes, n.getId()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    
    return new MenuItemDTO(root, children);
}

// DTO to prevent persistence
public record MenuItemDTO(
    ObjectId id,
    String refName,
    String displayName,
    List<MenuItemDTO> children,
    String sourceRealm
) {
    public MenuItemDTO(MenuItem item, List<MenuItemDTO> children) {
        this(item.getId(), item.getRefName(), item.getDisplayName(), 
             children, item.getModelSourceRealm());
    }
}
```

### 3.4 StaticDynamicList Multi-Item Filtering

**Requirement**: Filter by any-match instead of just first item.

**Implementation**:
```java
// MenuItemStaticDynamicListRepo.java
public List<MenuItem> findByStaticDynamicList(String realmId, String listName, String itemValue) {
    // Old: matched only first item
    // New: match any item in the list
    return ds.find(MenuItem.class)
        .filter(Filters.and(
            Filters.eq("staticDynamicLists.name", listName),
            Filters.eq("staticDynamicLists.items", itemValue) // MongoDB matches any array element
        ))
        .iterator()
        .toList();
}
```

## 4. Implementation Priority

### Phase 1 (Critical - Security)
1. Centralized modelSourceRealm setting
2. Anonymous fallback configuration
3. Unit tests for provenance

### Phase 2 (High - Data Integrity)
1. Optimistic locking support
2. Type coercion with logging
3. Transactional child add/remove

### Phase 3 (Medium - Performance)
1. Optimize getFilteredMenu with batched fetch
2. DTO pattern for read endpoints
3. StaticDynamicList any-match filtering

### Phase 4 (Low - Enhancement)
1. State transition validation in updateMany
2. Rename descendants → childrenIds
3. Documentation updates

## 5. Testing Strategy

### Unit Tests
- `testSourceRealmSetOnAllReadPaths()`
- `testAnonymousFallbackWhenConfigured()`
- `testOptimisticLockingVersionMismatch()`
- `testTypeCoercionWithLogging()`

### Integration Tests
- `testTransactionalChildOperations()`
- `testBatchedSubtreeFetch()`
- `testFilteredMenuWithPermissions()`

### Performance Tests
- Benchmark getFilteredMenu: recursive vs batched
- Measure type coercion overhead
- Test optimistic locking contention

## 6. Configuration

```properties
# Security
quantum.security.allow-anonymous-fallback=false

# Updates
quantum.updates.enable-type-coercion=true
quantum.updates.enable-optimistic-locking=true
quantum.updates.validate-state-transitions=false

# Hierarchy
quantum.hierarchy.use-batched-fetch=true
quantum.hierarchy.max-subtree-depth=10
```

## 7. Migration Path

1. Deploy code with feature flags disabled
2. Run database migrations (rename descendants)
3. Enable features incrementally per environment
4. Monitor logs for coercion warnings
5. Update client code to use DTOs
6. Remove deprecated fields after grace period
