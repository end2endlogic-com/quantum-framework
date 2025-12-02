# Seed Framework Audit - Executive Summary

## Quick Overview

The seed framework needs refactoring to align with Quarkus/CDI best practices and properly leverage the Morphia repository layer. Key issues include manual dependency construction, direct MongoDB access, and code duplication.

---

## Top 5 Critical Issues

### 1. **Manual Dependency Construction (Not CDI)**
- **Impact**: High
- **Location**: `SeedLoader` builder pattern used in 4+ places
- **Fix**: Create `@ApplicationScoped SeedLoaderService` bean

### 2. **Direct MongoDB Access Bypassing Repositories**
- **Impact**: High  
- **Location**: `MongoSeedRepository`, `MongoSeedRegistry`
- **Fix**: Use Morphia repos exclusively, remove direct `MongoClient` usage

### 3. **Inefficient Natural Key Lookups**
- **Impact**: High
- **Location**: `MorphiaSeedRepository.java:90` - loads ALL entities
- **Fix**: Use repository query methods: `repo.findByQuery(filters)`

### 4. **Code Duplication**
- **Impact**: Medium
- **Location**: Seed discovery logic in 4 classes
- **Fix**: Create `SeedDiscoveryService` to centralize logic

### 5. **Missing Transaction Management**
- **Impact**: Medium
- **Location**: All seed operations
- **Fix**: Add `@Transactional` to seed application methods

---

## Architecture Issues

| Component | Current State | Recommended State |
|-----------|--------------|------------------|
| `SeedLoader` | Final class, builder pattern | `@ApplicationScoped SeedLoaderService` |
| `MongoSeedRepository` | Direct MongoDB access | Use Morphia repos only |
| `MongoSeedRegistry` | Direct MongoDB access | Create `SeedRegistryEntry` entity + repo |
| `FileSeedSource` | Manual instantiation | `@ApplicationScoped` CDI bean |
| `ClasspathSeedSource` | Manual instantiation | `@ApplicationScoped` CDI bean |
| Seed Discovery | Duplicated in 4 places | `SeedDiscoveryService` |

---

## Refactoring Priority

### Phase 1 (Critical - Week 1)
1. ✅ Create `SeedLoaderService` as CDI bean
2. ✅ Remove direct MongoDB access from repositories
3. ✅ Fix natural key lookup inefficiency
4. ✅ Add transaction management

### Phase 2 (Important - Week 2)
5. ✅ Centralize seed discovery
6. ✅ Auto-discover seed sources via CDI
7. ✅ Create `SeedRegistryEntry` entity

### Phase 3 (Enhancement - Week 3)
8. ✅ Add observability (metrics, tracing)
9. ✅ Improve error handling
10. ✅ Add validation

---

## Key Code Changes Needed

### Before (Current)
```java
// Manual construction everywhere
SeedLoader loader = SeedLoader.builder()
    .addSeedSource(new FileSeedSource("files", root))
    .addSeedSource(new ClasspathSeedSource())
    .seedRepository(new MongoSeedRepository(mongoClient))
    .seedRegistry(new MongoSeedRegistry(mongoClient))
    .build();
```

### After (Recommended)
```java
@Inject
SeedLoaderService seedLoaderService;

// Auto-discovered dependencies
seedLoaderService.applySeeds(context, refs);
```

### Before (Inefficient)
```java
// Loads ALL entities into memory
List existingList = repo.getAllList(context.getRealm());
for (Object o : existingList) {
    if (matchesNaturalKey(e, record, naturalKey)) {
        matched = e;
        break;
    }
}
```

### After (Efficient)
```java
// Uses database query
List<Filter> filters = naturalKey.stream()
    .map(key -> Filters.eq(key, record.get(key)))
    .collect(Collectors.toList());
    
Optional<T> existing = repo.findByQuery(context.getRealm(), filters)
    .stream().findFirst();
```

---

## Testing Impact

- **Current**: Hard to test (manual mocking required)
- **After**: Easy to test (CDI test framework, mock beans)

## Performance Impact

- **Current**: Loads all entities for natural key lookup
- **After**: Database-level queries (10-100x faster for large datasets)

## Maintainability Impact

- **Current**: Code duplication, manual construction
- **After**: Single source of truth, auto-discovery

---

## Migration Path

1. **Week 1**: Implement new services alongside old code
2. **Week 2**: Feature flag to switch implementations
3. **Week 3**: Migrate callers one by one
4. **Week 4**: Remove old code, full rollout

---

## Estimated Effort

- **Total**: 10-15 days
- **Testing**: 3-5 days
- **Documentation**: 1-2 days

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Breaking existing seeds | Low | High | Feature flag, gradual migration |
| Performance regression | Low | Medium | Load testing before rollout |
| Missing edge cases | Medium | Medium | Comprehensive integration tests |

---

## Success Criteria

✅ All components are CDI beans  
✅ No direct MongoDB access  
✅ Natural key lookups use queries  
✅ Seed discovery centralized  
✅ Transaction management in place  
✅ Observability added  
✅ All tests passing  
✅ Performance improved or maintained  

---

For detailed analysis, see `SEED_FRAMEWORK_AUDIT.md`

