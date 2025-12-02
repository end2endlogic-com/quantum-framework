# Seed Framework Audit & Refactoring Recommendations

## Executive Summary

This audit evaluates the seed framework implementation in `quantum-framework` for CDI/Quarkus best practices, persistence layer usage, and seed discovery/execution patterns. The framework has several areas that need refactoring to align with modern Quarkus/CDI patterns and better leverage the Morphia repository layer.

---

## 1. CDI/Quarkus Best Practices Issues

### 1.1 Manual Dependency Construction (Builder Pattern Anti-Pattern)

**Problem:**
- `SeedLoader` uses a builder pattern and is manually constructed throughout the codebase
- Dependencies are manually instantiated instead of being injected via CDI
- This violates Quarkus/CDI dependency injection principles

**Locations:**
- `SeedStartupRunner.java:258-263` - Manual construction
- `SeedAdminResource.java:117-122, 160-165` - Manual construction  
- `TenantProvisioningService.java:265-269` - Manual construction

**Impact:**
- Cannot leverage CDI lifecycle management
- Difficult to test (requires manual mocking)
- Cannot use CDI interceptors, decorators, or events
- Configuration cannot be injected via `@ConfigProperty`

**Recommendation:**
```java
@ApplicationScoped
public class SeedLoaderService {
    @Inject
    MorphiaSeedRepository seedRepository;
    
    @Inject
    MongoSeedRegistry seedRegistry;
    
    @Inject
    Instance<SeedSource> seedSources; // Auto-discover all SeedSource beans
    
    @Inject
    ObjectMapper objectMapper;
    
    @ConfigProperty(name = "quantum.seed.conflict-policy", defaultValue = "SEED_WINS")
    SeedConflictPolicy conflictPolicy;
    
    public SeedLoader createLoader(SeedContext context) {
        // Build loader with injected dependencies
    }
}
```

### 1.2 Missing CDI Scopes

**Problem:**
- `SeedLoader` is a final class with no CDI annotations
- `MongoSeedRepository` and `MongoSeedRegistry` are final classes with no CDI annotations
- `FileSeedSource` and `ClasspathSeedSource` are final classes with no CDI annotations

**Impact:**
- Cannot be managed by CDI container
- Cannot use `@PostConstruct`/`@PreDestroy`
- Cannot leverage CDI events
- Manual lifecycle management required

**Recommendation:**
- Make `SeedLoader` a CDI bean (or create a `SeedLoaderService` wrapper)
- Convert repositories to CDI beans with appropriate scopes
- Make seed sources discoverable via CDI `Instance<SeedSource>`

### 1.3 Direct MongoClient Injection vs Repository Pattern

**Problem:**
- `MongoSeedRepository` and `MongoSeedRegistry` directly inject/use `MongoClient`
- Bypasses the Morphia repository layer entirely
- No security context, audit trails, or data domain handling

**Locations:**
- `MongoSeedRepository.java:22-26` - Direct MongoClient usage
- `MongoSeedRegistry.java:23-27` - Direct MongoClient usage
- `SeedStartupRunner.java:36` - Direct MongoClient injection

**Impact:**
- Seeds bypass security rules
- No audit logging
- No data domain enforcement
- Inconsistent with application's persistence strategy

### 1.4 Configuration Not Using MicroProfile Config

**Problem:**
- `SeedPathResolver` uses `ConfigProvider.getConfig()` directly (static access)
- Some configuration is hardcoded or uses static methods
- Not leveraging `@ConfigProperty` injection

**Location:**
- `SeedPathResolver.java:21-23` - Static config access

**Recommendation:**
```java
@ApplicationScoped
public class SeedPathResolver {
    @ConfigProperty(name = "quantum.seed-pack.root", defaultValue = "src/main/resources/seed-packs")
    String seedRoot;
    
    // Inject and use instance methods
}
```

### 1.5 Startup Logic in @PostConstruct

**Problem:**
- `SeedStartupRunner.onStart()` does heavy I/O and database operations
- Blocks application startup
- No proper error handling or retry mechanism
- Uses `@Startup` which runs synchronously
- `PendingSeedsStartupLogger` also duplicates seed discovery logic

**Locations:**
- `SeedStartupRunner.java:62-85`
- `PendingSeedsStartupLogger.java:29-53` - Duplicates discovery logic

**Recommendation:**
- Use Quarkus `@StartupEvent` with async execution
- Implement proper health checks
- Add observability/metrics
- Consider using Quarkus Scheduler for delayed execution
- Share discovery logic via `SeedDiscoveryService`

---

## 2. Persistence Layer Issues

### 2.1 Dual Repository Implementation Problem

**Problem:**
- `MorphiaSeedRepository` attempts to use Morphia repos but has fallback to direct MongoDB
- `MongoSeedRepository` completely bypasses Morphia
- Inconsistent behavior between the two implementations

**Location:**
- `MorphiaSeedRepository.java:64-117` - Complex fallback logic
- `MongoSeedRepository.java` - Direct MongoDB access

**Issues:**
1. **Inefficient Natural Key Lookup:**
   ```java
   // MorphiaSeedRepository.java:90
   List existingList = repo.getAllList(context.getRealm());
   ```
   - Loads ALL entities into memory to find one match
   - Should use repository query methods instead

2. **Missing Security Context:**
   - Direct MongoDB writes bypass security rules
   - No data domain enforcement
   - No audit trail

3. **Inconsistent Upsert Logic:**
   - `MongoSeedRepository` uses `replaceOne` with upsert
   - `MorphiaSeedRepository` loads all entities and matches in memory
   - Different behaviors for same operation

**Recommendation:**
- Create a unified `SeedRepository` that always uses Morphia repos
- For entities without repos, create a generic Morphia-based repository
- Remove direct MongoDB access entirely
- Use repository query methods for natural key lookups:
  ```java
  // Instead of getAllList()
  Optional<T> existing = repo.findByNaturalKey(context.getRealm(), naturalKeyFields);
  ```

### 2.2 Registry Should Use Repository Pattern

**Problem:**
- `MongoSeedRegistry` directly accesses MongoDB
- Should have a dedicated Morphia entity and repository
- No type safety or validation

**Location:**
- `MongoSeedRegistry.java:34-84` - Direct Document manipulation

**Recommendation:**
```java
@Entity("_seed_registry")
public class SeedRegistryEntry extends UnversionedBaseModel {
    private String seedPack;
    private String version;
    private String dataset;
    private String checksum;
    private Integer recordsApplied;
    private Instant appliedAt;
    // getters/setters
}

@ApplicationScoped
public class SeedRegistryRepository extends MorphiaRepo<SeedRegistryEntry> {
    public Optional<SeedRegistryEntry> findByPackAndDataset(String realm, String seedPack, 
                                                           String version, String dataset) {
        // Use repository query methods
    }
}
```

### 2.3 Missing Transaction Management

**Problem:**
- Seed operations are not transactional
- If a seed fails partway through, partial data may be persisted
- No rollback capability

**Recommendation:**
- Use `@Transactional` on seed application methods
- Consider batch operations with proper transaction boundaries
- Add idempotency checks at transaction level

---

## 3. Seed Discovery & Execution Issues

### 3.1 Duplicate Discovery Logic

**Problem:**
- Seed discovery logic is duplicated across multiple classes:
  - `SeedStartupRunner.java:218-238`
  - `SeedAdminResource.java:56-66, 128-138, 172-181`
  - `TenantProvisioningService.java:279-285`
  - `PendingSeedsStartupLogger.java:55-92` - Also duplicates checksum calculation

**Impact:**
- Code duplication
- Inconsistent behavior
- Hard to maintain
- Version comparison logic duplicated (`compareSemver` method exists in multiple places)

**Recommendation:**
```java
@ApplicationScoped
public class SeedDiscoveryService {
    @Inject
    Instance<SeedSource> seedSources;
    
    public List<SeedPackDescriptor> discoverSeedPacks(SeedContext context) {
        // Centralized discovery logic
    }
    
    public Map<String, SeedPackDescriptor> findLatestVersions(List<SeedPackDescriptor> descriptors) {
        // Centralized version resolution using Semver library
    }
    
    public String calculateChecksum(SeedPackDescriptor descriptor, Dataset dataset) {
        // Centralized checksum calculation
    }
}
```

### 3.2 Seed Sources Not Auto-Discoverable

**Problem:**
- `FileSeedSource` and `ClasspathSeedSource` are manually instantiated
- Cannot easily add new seed sources (S3, HTTP, etc.)
- No plugin mechanism

**Recommendation:**
```java
public interface SeedSource {
    // existing methods
}

@ApplicationScoped
public class FileSeedSource implements SeedSource {
    // Auto-discovered by CDI
}

@ApplicationScoped  
public class ClasspathSeedSource implements SeedSource {
    // Auto-discovered by CDI
}

// New sources can be added as CDI beans
@ApplicationScoped
public class S3SeedSource implements SeedSource {
    // Automatically discovered
}
```

### 3.3 Inefficient Seed Source Resolution

**Problem:**
- `SeedResourceRouter` is created per `SeedLoader` instance
- Seed sources are manually added via builder
- No caching of discovered seed packs

**Location:**
- `SeedLoader.java:55` - Resource router creation
- `SeedLoader.java:290-303` - Resolution happens every time

**Recommendation:**
- Cache discovered seed packs with TTL
- Use CDI `Instance<SeedSource>` for auto-discovery
- Implement seed pack caching service

### 3.4 Missing Observability

**Problem:**
- No metrics for seed execution
- Limited logging
- No tracing/span support
- Cannot monitor seed performance

**Recommendation:**
- Add Micrometer metrics
- Use Quarkus OpenTelemetry
- Add structured logging
- Track seed execution times and success rates

---

## 4. Security & Context Issues

### 4.1 Security Context Handling

**Problem:**
- `SeedStartupRunner` manually constructs security contexts
- Context is set/unset manually with try-finally
- Inconsistent security context usage

**Location:**
- `SeedStartupRunner.java:186-215` - Manual context setup
- `SeedStartupRunner.java:272-278` - Manual context cleanup

**Recommendation:**
- Use CDI `@ActivateRequestContext` or similar
- Create a `SeedSecurityContext` service
- Leverage Quarkus security context propagation

### 4.2 Missing Validation

**Problem:**
- No validation of seed pack manifests
- No schema validation for datasets
- No validation of natural keys

**Recommendation:**
- Add Bean Validation annotations
- Validate manifests against schema
- Validate dataset records before persistence

---

## 5. Refactoring Recommendations

### 5.1 Proposed Architecture

```
┌─────────────────────────────────────────────────────────┐
│              Seed Framework (CDI-based)                 │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────────┐    ┌──────────────────┐          │
│  │ SeedLoaderService│───▶│ SeedDiscoverySvc │          │
│  │  (@Application   │    │  (@Application   │          │
│  │   Scoped)        │    │   Scoped)        │          │
│  └──────────────────┘    └──────────────────┘          │
│         │                       │                       │
│         │                       ▼                       │
│         │              ┌──────────────────┐             │
│         │              │  SeedSource[]    │             │
│         │              │  (CDI Instance)  │             │
│         │              └──────────────────┘             │
│         │                       │                       │
│         ▼                       │                       │
│  ┌──────────────────┐          │                       │
│  │ SeedRepository   │          │                       │
│  │  (@Application   │          │                       │
│  │   Scoped)        │          │                       │
│  └──────────────────┘          │                       │
│         │                       │                       │
│         ▼                       │                       │
│  ┌──────────────────┐          │                       │
│  │ MorphiaRepo<T>[] │          │                       │
│  │  (CDI Instance)  │          │                       │
│  └──────────────────┘          │                       │
│                                                         │
│  ┌──────────────────┐                                  │
│  │ SeedRegistryRepo │                                  │
│  │  (MorphiaRepo)    │                                  │
│  └──────────────────┘                                  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 5.2 Step-by-Step Refactoring Plan

#### Phase 1: CDI-ify Core Components
1. Create `SeedLoaderService` as `@ApplicationScoped` bean
2. Convert `MongoSeedRepository` → `MorphiaSeedRepository` (remove fallback)
3. Create `SeedRegistryEntry` entity and repository
4. Convert seed sources to CDI beans

#### Phase 2: Improve Discovery
1. Create `SeedDiscoveryService` to centralize discovery
2. Implement seed pack caching
3. Auto-discover seed sources via CDI `Instance<SeedSource>`

#### Phase 3: Enhance Persistence
1. Remove all direct MongoDB access
2. Use repository query methods for natural key lookups
3. Add transaction management
4. Implement batch operations

#### Phase 4: Add Observability
1. Add metrics
2. Add tracing
3. Improve logging
4. Add health checks

#### Phase 5: Security & Validation
1. Centralize security context handling
2. Add validation
3. Add audit logging

### 5.3 Key Code Changes

#### SeedLoaderService (New)
```java
@ApplicationScoped
public class SeedLoaderService {
    
    @Inject
    SeedRepository seedRepository;
    
    @Inject
    SeedRegistry seedRegistry;
    
    @Inject
    Instance<SeedSource> seedSources;
    
    @Inject
    SeedDiscoveryService discoveryService;
    
    @ConfigProperty(name = "quantum.seed.conflict-policy")
    SeedConflictPolicy conflictPolicy;
    
    @Transactional
    public void applySeeds(SeedContext context, List<SeedPackRef> refs) {
        SeedLoader loader = createLoader(context);
        loader.apply(refs, context);
    }
    
    private SeedLoader createLoader(SeedContext context) {
        SeedLoader.Builder builder = SeedLoader.builder()
            .seedRepository(seedRepository)
            .seedRegistry(seedRegistry)
            .conflictPolicy(conflictPolicy);
            
        seedSources.forEach(builder::addSeedSource);
        
        return builder.build();
    }
}
```

#### SeedRepository (Refactored)
```java
@ApplicationScoped
public class MorphiaSeedRepository implements SeedRepository {
    
    @Inject
    Instance<BaseMorphiaRepo<? extends UnversionedBaseModel>> repos;
    
    @Inject
    MorphiaDataStore morphiaDataStore;
    
    @Override
    public void upsertRecord(SeedContext context, Dataset dataset, Map<String, Object> record) {
        Optional<BaseMorphiaRepo<?>> repoOpt = findRepo(dataset);
        
        if (repoOpt.isEmpty()) {
            throw new IllegalStateException("No repository found for modelClass: " + dataset.getModelClass());
        }
        
        BaseMorphiaRepo<?> repo = repoOpt.get();
        UnversionedBaseModel entity = convertToEntity(record, repo.getPersistentClass());
        
        // Use repository query for natural key lookup
        if (!dataset.getNaturalKey().isEmpty()) {
            Optional<?> existing = findExistingByNaturalKey(repo, context, dataset, record);
            if (existing.isPresent()) {
                if (!dataset.isUpsert()) {
                    return; // Skip insert-only
                }
                entity.setId(((UnversionedBaseModel)existing.get()).getId());
            }
        }
        
        repo.save(context.getRealm(), entity);
    }
    
    private Optional<?> findExistingByNaturalKey(BaseMorphiaRepo<?> repo, 
                                                 SeedContext context,
                                                 Dataset dataset,
                                                 Map<String, Object> record) {
        // Build query using repository's query builder
        // Instead of loading all entities
        List<Filter> filters = dataset.getNaturalKey().stream()
            .map(key -> Filters.eq(key, record.get(key)))
            .collect(Collectors.toList());
            
        Datastore datastore = morphiaDataStore.getDataStore(context.getRealm());
        Query<?> query = datastore.find(repo.getPersistentClass())
            .filter(filters.toArray(new Filter[0]));
            
        return Optional.ofNullable(query.first());
    }
}
```

#### SeedRegistry (Refactored)
```java
@Entity("_seed_registry")
public class SeedRegistryEntry extends UnversionedBaseModel {
    @Property("seedPack")
    private String seedPack;
    
    @Property("version")
    private String version;
    
    @Property("dataset")
    private String dataset;
    
    @Property("checksum")
    private String checksum;
    
    @Property("records")
    private Integer recordsApplied;
    
    @Property("appliedAt")
    private Instant appliedAt;
    
    // getters/setters
}

@ApplicationScoped
public class SeedRegistryRepository extends MorphiaRepo<SeedRegistryEntry> {
    
    public Optional<SeedRegistryEntry> findEntry(String realm, String seedPack, 
                                                String version, String dataset) {
        List<Filter> filters = List.of(
            Filters.eq("seedPack", seedPack),
            Filters.eq("version", version),
            Filters.eq("dataset", dataset)
        );
        
        return findByQuery(realm, filters).stream().findFirst();
    }
}

@ApplicationScoped
public class MorphiaSeedRegistry implements SeedRegistry {
    
    @Inject
    SeedRegistryRepository registryRepo;
    
    @Override
    public Optional<String> getLastAppliedChecksum(SeedContext context,
                                                  SeedPackManifest manifest,
                                                  Dataset dataset) {
        return registryRepo.findEntry(context.getRealm(), 
                                     manifest.getSeedPack(),
                                     manifest.getVersion(),
                                     dataset.getCollection())
            .map(SeedRegistryEntry::getChecksum);
    }
    
    @Override
    @Transactional
    public void recordApplied(SeedContext context, SeedPackManifest manifest,
                             Dataset dataset, String checksum, int recordsApplied) {
        SeedRegistryEntry entry = registryRepo.findEntry(...)
            .orElse(new SeedRegistryEntry());
            
        entry.setSeedPack(manifest.getSeedPack());
        entry.setVersion(manifest.getVersion());
        entry.setDataset(dataset.getCollection());
        entry.setChecksum(checksum);
        entry.setRecordsApplied(recordsApplied);
        entry.setAppliedAt(Instant.now());
        
        registryRepo.save(context.getRealm(), entry);
    }
}
```

---

## 6. Migration Strategy

### 6.1 Backward Compatibility
- Keep existing `SeedLoader` builder API during transition
- Create adapter/wrapper for old API
- Gradual migration path

### 6.2 Testing Strategy
1. Unit tests for new CDI beans
2. Integration tests for seed discovery
3. End-to-end tests for seed application
4. Performance tests for large seed packs

### 6.3 Rollout Plan
1. Implement new services alongside old code
2. Feature flag to switch between old/new implementations
3. Monitor and compare behavior
4. Gradually migrate callers
5. Remove old code once stable

---

## 7. Additional Issues Found

### 7.1 Version Comparison Logic Duplication

**Problem:**
- `compareSemver` method is duplicated in:
  - `SeedStartupRunner.java:290-300`
  - `SeedAdminResource.java:239-248`
  - `PendingSeedsStartupLogger.java:95-108`
- All implement basic semver comparison but could use `org.semver4j.Semver` library (already imported in `SeedLoader`)

**Recommendation:**
- Create a `VersionComparator` utility class
- Use `Semver` library consistently
- Centralize version comparison logic

### 7.2 PendingSeedsStartupLogger Issues

**Problem:**
- `PendingSeedsStartupLogger` manually constructs `FileSeedSource` and `MongoSeedRegistry`
- Duplicates seed discovery and checksum calculation
- Uses `@Startup` which blocks startup
- No error recovery

**Location:**
- `PendingSeedsStartupLogger.java:55-92`

**Recommendation:**
- Inject `SeedDiscoveryService` instead of manual construction
- Use async startup or scheduled task
- Share checksum calculation logic

### 7.3 SeedContext Not a CDI Bean

**Problem:**
- `SeedContext` is a value object (immutable) which is fine
- But it's manually constructed everywhere
- Could benefit from a factory/builder service

**Recommendation:**
```java
@ApplicationScoped
public class SeedContextFactory {
    public SeedContext createContext(String realm, Optional<DataDomain> dataDomain) {
        // Centralized context creation
    }
}
```

## 8. Summary of Critical Issues

### High Priority
1. ✅ **Remove direct MongoDB access** - Use Morphia repos exclusively
2. ✅ **CDI-ify all components** - Proper dependency injection
3. ✅ **Fix inefficient natural key lookups** - Use repository queries
4. ✅ **Add transaction management** - Ensure data consistency

### Medium Priority
5. ✅ **Centralize seed discovery** - Remove duplication
6. ✅ **Auto-discover seed sources** - CDI-based plugin system
7. ✅ **Add observability** - Metrics, tracing, logging

### Low Priority
8. ✅ **Improve error handling** - Better error messages and recovery
9. ✅ **Add validation** - Validate manifests and datasets
10. ✅ **Documentation** - API documentation and examples

---

## 9. Estimated Effort

- **Phase 1 (CDI-ify)**: 2-3 days
- **Phase 2 (Discovery)**: 1-2 days  
- **Phase 3 (Persistence)**: 3-4 days
- **Phase 4 (Observability)**: 1-2 days
- **Phase 5 (Security)**: 1-2 days

**Total**: ~10-15 days of development + testing

---

## Conclusion

The seed framework has a solid foundation but needs refactoring to align with Quarkus/CDI best practices and properly leverage the Morphia repository layer. The main issues are:

1. Manual dependency construction instead of CDI
2. Direct MongoDB access bypassing repositories
3. Inefficient natural key lookups
4. Duplicated discovery logic
5. Missing observability and transaction management

The proposed refactoring will make the framework more maintainable, testable, and consistent with the rest of the application architecture.

