# Ontology Realm Scoping Analysis & Migration Plan

## Executive Summary

**Current State**: Ontology TBox is implemented as GLOBAL (single instance across all realms)
**Problem**: Realms can have different applications, schemas, and ontologies
**Required**: Make ontology realm-specific while maintaining performance and consistency

---

## Current Architecture Analysis

### What's Currently Global

1. **OntologyMeta** - Singleton with `refName="global"`
   - Stores: yamlHash, tboxHash, yamlVersion, source
   - Index: Unique on `refName` only
   - Repository: Uses `defaultRealm` datastore
   - **Assumption**: One TBox for entire deployment

2. **OntologyRegistry** - ApplicationScoped CDI bean
   - Produced once per application
   - Shared across all requests regardless of realm
   - **Assumption**: All realms use same ontology

3. **OntologyRegistryProducer** - Creates single in-memory TBox
   - Hardcoded Person/Agent/knows example
   - **Assumption**: Static ontology for all realms

### What's Currently Realm-Scoped

1. **OntologyEdge** - Instance data (ABox)
   - Indexed by `dataDomain.tenantId`
   - Unique constraint includes tenantId
   - **Correctly scoped**: Each realm has own edges

2. **OntologyEdgeRepo** - Queries filtered by tenantId
   - Methods accept `tenantId` parameter
   - **Correctly scoped**: Realm-aware queries

### Critical Assumptions Being Made

1. **Single Application Assumption**
   ```java
   // OntologyMeta.java line 16-17
   // "The ontology TBox is shared across all realms"
   ```
   **Reality**: Different realms = different applications = different ontologies

2. **Singleton Pattern**
   ```java
   // OntologyMetaRepo.java
   .filter(Filters.eq("refName", "global"))
   ```
   **Reality**: Need one per realm, not one global

3. **DefaultRealm Usage**
   ```java
   // OntologyMetaRepo.java line 57
   morphiaDataStoreWrapper.getDataStore(defaultRealm)
   ```
   **Reality**: Should use specific realm's datastore

4. **ApplicationScoped Registry**
   ```java
   // OntologyRegistryProducer.java line 13
   @ApplicationScoped
   public OntologyRegistry produceRegistry()
   ```
   **Reality**: Need realm-specific registries

---

## Realm Model Analysis

### Realm Structure
```java
public class Realm {
    String emailDomain;        // e.g., "customer1.com"
    String connectionString;   // MongoDB connection
    String databaseName;       // Separate DB per realm
    DomainContext domainContext;
}
```

### Key Insights
- Each realm has **separate database**
- Each realm represents **different customer/application**
- Realms are **completely isolated** (different MongoDB databases)
- No shared data between realms (except framework metadata)

---

## Problems with Current Global Approach

### Problem 1: Schema Conflicts
**Scenario**: 
- Realm A (Healthcare): Has `Patient`, `Doctor`, `Diagnosis` classes
- Realm B (E-commerce): Has `Customer`, `Product`, `Order` classes

**Current Behavior**: Both forced to use same ontology
**Impact**: Cannot model domain-specific concepts

### Problem 2: Version Skew
**Scenario**:
- Realm A deploys ontology v1.0
- Realm B deploys ontology v2.0 (breaking changes)

**Current Behavior**: Last deployment wins, breaks other realm
**Impact**: Cannot evolve ontologies independently

### Problem 3: Multi-Tenancy Violation
**Scenario**:
- Realm A updates ontology
- Triggers reindex for ALL realms

**Current Behavior**: Global `reindexRequired` flag
**Impact**: Performance impact across unrelated realms

### Problem 4: Security Boundary
**Scenario**:
- Realm A is PCI-compliant environment
- Realm B is development environment

**Current Behavior**: Share same ontology metadata
**Impact**: Compliance violation (dev can see prod schema)

---

## Required Changes

### Phase 1: Model Changes

#### 1.1 OntologyMeta - Add Realm Scoping
```java
// BEFORE
@Index(options = @IndexOptions(name = "idx_meta_singleton", unique = true), 
       fields = { @Field("refName") })

// AFTER
@Index(options = @IndexOptions(name = "idx_meta_per_realm", unique = true), 
       fields = { @Field("refName"), @Field("dataDomain.tenantId") })
```

**Changes**:
- Remove "global" singleton concept
- Add `realmId` field (or use existing `dataDomain.tenantId`)
- Unique constraint: `(refName="ontology", realmId)`
- Each realm has own OntologyMeta record

#### 1.2 OntologyMeta - Field Semantics
```java
// Current: Global fields
private String yamlHash;      // One hash for all realms
private String tboxHash;      // One TBox for all realms

// Required: Realm-specific fields
private String realmId;       // NEW: Which realm this belongs to
private String yamlHash;      // Hash of THIS realm's YAML
private String tboxHash;      // Hash of THIS realm's TBox
private String yamlSource;    // Path to THIS realm's ontology file
```

### Phase 2: Repository Changes

#### 2.1 OntologyMetaRepo - Realm-Aware Methods
```java
// BEFORE
public Optional<OntologyMeta> getSingleton() {
    return ds().find(OntologyMeta.class)
        .filter(Filters.eq("refName", "global"))
        .first();
}

// AFTER
public Optional<OntologyMeta> getByRealm(String realmId) {
    return ds(realmId).find(OntologyMeta.class)
        .filter(Filters.and(
            Filters.eq("refName", "ontology"),
            Filters.eq("dataDomain.tenantId", realmId)
        ))
        .first();
}
```

**Changes**:
- All methods accept `realmId` parameter
- Use realm-specific datastore: `ds(realmId)`
- Filter by both refName AND realmId
- Remove "global" concept entirely

#### 2.2 OntologyMetaRepo - Atomic Operations
```java
// BEFORE
.filter(Filters.eq("refName", "global"))

// AFTER
.filter(Filters.and(
    Filters.eq("refName", "ontology"),
    Filters.eq("dataDomain.tenantId", realmId)
))
```

### Phase 3: Registry Changes

#### 3.1 OntologyRegistry - Realm-Scoped Instances
```java
// BEFORE: Single ApplicationScoped instance
@ApplicationScoped
public OntologyRegistry produceRegistry() {
    return OntologyRegistry.inMemory(tbox);
}

// AFTER: Realm-specific instances with caching
@ApplicationScoped
public class OntologyRegistryManager {
    private final Map<String, OntologyRegistry> registries = new ConcurrentHashMap<>();
    
    public OntologyRegistry getRegistry(String realmId) {
        return registries.computeIfAbsent(realmId, this::loadRegistry);
    }
    
    private OntologyRegistry loadRegistry(String realmId) {
        // Load realm-specific YAML
        // Create realm-specific TBox
        // Return realm-specific registry
    }
}
```

**Changes**:
- Replace single registry with registry manager
- Cache registries per realm
- Lazy-load on first access
- Support hot-reload per realm

#### 3.2 Registry Lifecycle
```java
public interface OntologyRegistryManager {
    OntologyRegistry getRegistry(String realmId);
    void reloadRegistry(String realmId);
    void clearRegistry(String realmId);
    Set<String> getLoadedRealms();
}
```

### Phase 4: Resource Layer Changes

#### 4.1 OntologyResource - Realm Parameter
```java
// BEFORE
@Inject OntologyRegistry registry;

@GET
@Path("/properties")
public Response listProperties() {
    return Response.ok(registry.properties()).build();
}

// AFTER
@Inject OntologyRegistryManager registryManager;

@GET
@Path("/properties")
public Response listProperties(@HeaderParam("X-Realm") String realmId) {
    OntologyRegistry registry = registryManager.getRegistry(realmId);
    return Response.ok(registry.properties()).build();
}
```

**Changes**:
- All endpoints require `X-Realm` header
- Inject manager instead of registry
- Get realm-specific registry per request
- Return 400 if realm not found

### Phase 5: Configuration Changes

#### 5.1 Realm-Specific Ontology Files
```properties
# BEFORE: Single global ontology
quantum.ontology.yaml=/ontology.yaml

# AFTER: Per-realm ontology files
quantum.ontology.yaml.realm.healthcare=/ontologies/healthcare.yaml
quantum.ontology.yaml.realm.ecommerce=/ontologies/ecommerce.yaml
quantum.ontology.yaml.default=/ontologies/default.yaml
```

#### 5.2 Realm Discovery
```java
// Discover realms from Realm collection
List<Realm> realms = realmRepo.findAll();
for (Realm realm : realms) {
    String yamlPath = config.getOntologyPath(realm.getRefName());
    registryManager.loadRegistry(realm.getRefName(), yamlPath);
}
```

---

## Migration Strategy

### Step 1: Add Realm Field (Non-Breaking)
- Add `realmId` field to OntologyMeta
- Keep existing "global" records
- Dual-write: populate both global and realm-specific records
- **Duration**: 1 week
- **Risk**: LOW

### Step 2: Update Repositories (Backward Compatible)
- Add realm-aware methods alongside existing
- Deprecate global methods
- Default to `defaultRealm` if realmId not provided
- **Duration**: 1 week
- **Risk**: LOW

### Step 3: Introduce Registry Manager
- Create OntologyRegistryManager
- Keep existing single registry as fallback
- Gradually migrate callers to use manager
- **Duration**: 2 weeks
- **Risk**: MEDIUM

### Step 4: Update Resource Layer
- Make `X-Realm` header required
- Return 400 if missing
- Use realm-specific registries
- **Duration**: 1 week
- **Risk**: MEDIUM

### Step 5: Data Migration
- For each realm in Realm collection:
  - Create OntologyMeta record with realmId
  - Copy yamlHash/tboxHash from global record
  - Mark for reindex if needed
- Delete global record
- **Duration**: 1 week
- **Risk**: HIGH

### Step 6: Remove Global Code
- Remove "global" singleton logic
- Remove backward compatibility code
- Update documentation
- **Duration**: 1 week
- **Risk**: LOW

---

## Detailed Implementation Plan

### Data Model Changes

#### OntologyMeta.java
```java
// Add field
private String realmId;  // Which realm this ontology belongs to

// Update index
@Indexes({
    @Index(options = @IndexOptions(name = "idx_meta_per_realm", unique = true), 
           fields = { @Field("refName"), @Field("realmId") })
})

// Update javadoc
/**
 * OntologyMeta is REALM-SPECIFIC (one per realm).
 * Each realm can have its own ontology TBox.
 * Singleton per realm enforced by unique index on (refName, realmId).
 */
```

### Repository Changes

#### OntologyMetaRepo.java
```java
// Replace getSingleton()
@Deprecated
public Optional<OntologyMeta> getSingleton() {
    return getByRealm(defaultRealm);
}

// Add realm-aware method
public Optional<OntologyMeta> getByRealm(String realmId) {
    return ds(realmId).find(OntologyMeta.class)
        .filter(Filters.and(
            Filters.eq("refName", "ontology"),
            Filters.eq("realmId", realmId)
        ))
        .first();
}

// Update upsertObservation
public OntologyMeta upsertObservation(String realmId, Integer yamlVersion, 
                                      String source, boolean reindexRequired) {
    Date now = new Date();
    return ds(realmId).find(OntologyMeta.class)
        .filter(Filters.and(
            Filters.eq("refName", "ontology"),
            Filters.eq("realmId", realmId)
        ))
        .modify(...)
        .execute(...);
}

// Update markApplied
public OntologyMeta markApplied(String realmId, String yamlHash, 
                                String tboxHash, Integer yamlVersion) {
    // Similar changes
}

// Add helper
private Datastore ds(String realmId) {
    return morphiaDataStoreWrapper.getDataStore(realmId);
}
```

### Registry Manager

#### OntologyRegistryManager.java (NEW)
```java
@ApplicationScoped
public class OntologyRegistryManager {
    
    @Inject MorphiaDataStoreWrapper datastoreWrapper;
    @Inject OntologyMetaRepo metaRepo;
    
    private final Map<String, OntologyRegistry> registries = new ConcurrentHashMap<>();
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    
    public OntologyRegistry getRegistry(String realmId) {
        if (realmId == null || realmId.isBlank()) {
            throw new IllegalArgumentException("realmId required");
        }
        
        return registries.computeIfAbsent(realmId, this::loadRegistry);
    }
    
    public void reloadRegistry(String realmId) {
        ReentrantReadWriteLock lock = locks.computeIfAbsent(realmId, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        try {
            registries.remove(realmId);
            loadRegistry(realmId);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private OntologyRegistry loadRegistry(String realmId) {
        // 1. Load YAML for this realm
        String yamlPath = getYamlPath(realmId);
        TBox tbox = new YamlOntologyLoader().loadFromClasspath(yamlPath);
        
        // 2. Compute hashes
        String yamlHash = computeYamlHash(yamlPath);
        String tboxHash = TBoxHasher.computeHash(tbox);
        
        // 3. Update metadata
        Optional<OntologyMeta> meta = metaRepo.getByRealm(realmId);
        Integer version = meta.map(OntologyMeta::getYamlVersion).orElse(1);
        metaRepo.markApplied(realmId, yamlHash, tboxHash, version);
        
        // 4. Create registry
        return new YamlBackedOntologyRegistry(tbox, yamlHash, false);
    }
    
    private String getYamlPath(String realmId) {
        // Check config for realm-specific path
        String path = ConfigProvider.getConfig()
            .getOptionalValue("quantum.ontology.yaml.realm." + realmId, String.class)
            .orElse("/ontologies/" + realmId + ".yaml");
        return path;
    }
}
```

### Resource Layer Changes

#### OntologyResource.java
```java
// Replace injection
@Inject OntologyRegistryManager registryManager;

// Update all endpoints
@GET
@Path("/properties")
public Response listProperties(@HeaderParam("X-Realm") @NotNull String realmId) {
    try {
        OntologyRegistry registry = registryManager.getRegistry(realmId);
        return Response.ok(registry.properties()).build();
    } catch (IllegalArgumentException e) {
        return Response.status(400)
            .entity(Map.of("error", "Invalid or missing X-Realm header"))
            .build();
    }
}

// Add admin endpoint to reload
@POST
@Path("/reload/{realmId}")
@RolesAllowed("admin")
public Response reloadOntology(@PathParam("realmId") String realmId) {
    registryManager.reloadRegistry(realmId);
    return Response.ok(Map.of("reloaded", realmId)).build();
}
```

---

## Testing Strategy

### Unit Tests
1. **OntologyMetaRepo**
   - Test realm-specific queries
   - Test unique constraint per realm
   - Test concurrent updates per realm

2. **OntologyRegistryManager**
   - Test lazy loading
   - Test caching
   - Test reload
   - Test concurrent access

### Integration Tests
1. **Multi-Realm Scenarios**
   - Create 2 realms with different ontologies
   - Verify isolation
   - Verify independent updates

2. **Migration Tests**
   - Test global → realm-specific migration
   - Verify data integrity
   - Test rollback

### Performance Tests
1. **Registry Loading**
   - Measure load time per realm
   - Test cache hit rate
   - Test memory usage with 100 realms

2. **Concurrent Access**
   - Test 1000 requests across 10 realms
   - Verify no lock contention
   - Measure latency

---

## Rollback Plan

### If Migration Fails

**Step 1**: Revert code deployment
**Step 2**: Restore global OntologyMeta record
```javascript
// MongoDB shell
db.ontology_meta.insertOne({
    refName: "global",
    yamlHash: "<last_known_hash>",
    tboxHash: "<last_known_hash>",
    ...
});
```

**Step 3**: Drop realm-specific records
```javascript
db.ontology_meta.deleteMany({realmId: {$exists: true}});
```

**Step 4**: Restart application

---

## Success Criteria

1. ✓ Each realm has independent OntologyMeta record
2. ✓ Realms can have different ontologies
3. ✓ Updates to one realm don't affect others
4. ✓ No performance degradation
5. ✓ Backward compatible during migration
6. ✓ Zero downtime deployment

---

## Timeline

| Week | Phase | Deliverable |
|------|-------|-------------|
| 1 | Analysis | This document |
| 2-3 | Phase 1 | Model changes + tests |
| 4-5 | Phase 2 | Repository changes + tests |
| 6-7 | Phase 3 | Registry manager + tests |
| 8 | Phase 4 | Resource layer + tests |
| 9 | Phase 5 | Data migration + validation |
| 10 | Phase 6 | Cleanup + documentation |

**Total**: 10 weeks

---

## Open Questions

1. **Ontology Discovery**: How to discover which YAML file to load for each realm?
   - Option A: Convention-based (`/ontologies/{realmId}.yaml`)
   - Option B: Configuration-based (per-realm config)
   - Option C: Database-based (store path in Realm model)

2. **Default Ontology**: What if realm has no specific ontology?
   - Option A: Fail fast (require explicit ontology)
   - Option B: Use default/empty ontology
   - Option C: Inherit from parent realm

3. **Hot Reload**: Should ontology changes require restart?
   - Option A: Manual reload via admin endpoint
   - Option B: File watcher with auto-reload
   - Option C: Version-based reload on next request

4. **Memory Management**: How to handle 1000+ realms?
   - Option A: LRU cache with eviction
   - Option B: Load on demand, never evict
   - Option C: Preload common realms, lazy-load others

---

## Recommendations

1. **Start with Phase 1-2**: Low risk, high value
2. **Use Option A for discovery**: Convention-based paths
3. **Use Option B for defaults**: Empty ontology if missing
4. **Use Option A for reload**: Manual admin endpoint
5. **Use Option C for memory**: Preload + lazy-load hybrid
