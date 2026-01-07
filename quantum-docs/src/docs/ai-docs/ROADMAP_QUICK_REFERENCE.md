# Implementation Roadmap - Quick Reference

## Timeline Overview
```
┌─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
│  Week 1-2   │  Week 3-4   │   Week 5    │   Week 6    │  Week 7-9   │
├─────────────┼─────────────┼─────────────┼─────────────┼─────────────┤
│  Phase 1    │  Phase 2    │  Phase 3    │  Phase 4    │  Phase 5-6  │
│  Security   │  Updates    │  State Val  │  DTOs       │  Hierarchy  │
│  Provenance │  Safety     │             │             │  + Lists    │
└─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘
```

## Phase Summary

| Phase | Focus | Risk | DB Changes | Breaking |
|-------|-------|------|------------|----------|
| 1 | Security & Provenance | LOW | No | No |
| 2 | Update Safety | MED | Yes (version) | No |
| 3 | State Validation | LOW | No | No |
| 4 | DTO Pattern | LOW | No | No |
| 5 | Hierarchy Optimization | MED | Yes (rename) | No |
| 6 | List Filtering | LOW | No | No |

## Key Deliverables by Phase

### Phase 1: Security & Provenance
```java
// Helper methods
protected <T> T setSourceRealm(T entity, String realmId)
protected SecurityContext ensureSecurityContextFromIdentity(SecurityIdentity identity)

// Config
quantum.security.allow-anonymous-fallback=false
```

### Phase 2: Update Safety
```java
// Optimistic locking
OptimisticUpdateResult updateWithVersion(Datastore ds, ObjectId id, Long version, ...)

// Type coercion
Object coerce(Object value, Class<?> targetType)

// Model change
@Version protected Long version;
```

### Phase 3: State Validation
```java
// Validation
UpdateManyResult updateManyWithValidation(Filter filter, String targetState, 
                                          StateTransitionValidator validator)

// Interface
interface StateTransitionValidator {
    boolean isValidTransition(String from, String to);
}
```

### Phase 4: DTO Pattern
```java
// Wrapper
public class EntityDTO<T> {
    private T entity;
    private String sourceRealm;
    private Date readAt;
}

// Usage
@GET Response getById() {
    return Response.ok(EntityDTO.from(entity, realmId)).build();
}
```

### Phase 5: Hierarchy Optimization
```java
// Batched fetch
MenuItemDTO getFilteredMenu(String realmId, ObjectId rootId, Set<String> identities)

// Transactional ops
@Transactional void addChild(String realmId, ObjectId parentId, ObjectId childId)
@Transactional void removeChild(String realmId, ObjectId parentId, ObjectId childId)

// Field rename
@Deprecated List<ObjectId> descendants;
List<ObjectId> childrenIds;
```

### Phase 6: StaticDynamicList
```java
// Any-match query
Filters.eq("staticDynamicLists.items", itemValue) // Matches any array element
```

## Critical Path

```
Phase 1 (Security) → Phase 2 (Updates) → Phase 4 (DTOs)
                                       ↘
                                         Phase 5 (Hierarchy)
                                       ↗
                     Phase 3 (State) → Phase 6 (Lists)
```

## Configuration Checklist

```properties
# Phase 1
quantum.security.allow-anonymous-fallback=false

# Phase 2
quantum.updates.enable-type-coercion=true
quantum.updates.enable-optimistic-locking=true

# Phase 3
quantum.updates.validate-state-transitions=false

# Phase 5
quantum.hierarchy.use-batched-fetch=true
quantum.hierarchy.max-subtree-depth=10
```

## Migration Checklist

### Phase 2: Add Version Field
- [ ] Deploy code with version field (nullable)
- [ ] Backfill existing records: `db.collection.updateMany({version: null}, {$set: {version: 1}})`
- [ ] Monitor version conflicts in logs
- [ ] Update client code to handle 409 responses

### Phase 5: Rename descendants → childrenIds
- [ ] Deploy code with both fields
- [ ] Run migration: `db.collection.updateMany({}, {$rename: {descendants: "childrenIds"}})`
- [ ] Verify data integrity
- [ ] Remove deprecated field in next release

## Testing Priorities

### Must Test
- Phase 1: Provenance on all read paths
- Phase 2: Version conflict handling
- Phase 5: Batched fetch performance
- Phase 5: Transactional child operations

### Should Test
- Phase 2: Type coercion edge cases
- Phase 3: State transition validation
- Phase 4: DTO serialization
- Phase 6: Multi-item filtering

### Nice to Test
- Phase 1: Anonymous fallback
- Phase 4: DTO prevents persistence
- Phase 5: Deep hierarchy performance

## Rollback Procedures

### Phase 1-4 (Code Only)
```bash
# Revert deployment
kubectl rollout undo deployment/quantum-api
# No database changes needed
```

### Phase 2 (With Version Field)
```bash
# Revert deployment
kubectl rollout undo deployment/quantum-api
# Version field remains but unused (safe)
```

### Phase 5 (With Field Rename)
```bash
# Revert deployment
kubectl rollout undo deployment/quantum-api
# Restore old field name
db.collection.updateMany({}, {$rename: {childrenIds: "descendants"}})
```

## Success Metrics

| Phase | Metric | Target |
|-------|--------|--------|
| 1 | Missing sourceRealm | 0 |
| 2 | Version conflicts | < 1% |
| 2 | Type coercion warnings | < 0.1% |
| 3 | Invalid transitions | < 2% |
| 5 | Query reduction | N → 1 |
| 5 | Latency improvement | -80% |

## Risk Matrix

```
High Risk:
  - Phase 2: Version field migration
  - Phase 5: Hierarchy migration

Medium Risk:
  - Phase 2: Type coercion
  - Phase 5: $graphLookup performance

Low Risk:
  - Phase 1: Security enhancements
  - Phase 3: State validation
  - Phase 4: DTO pattern
  - Phase 6: List filtering
```

## Dependencies

### MongoDB Version
- Phase 5 requires MongoDB 3.4+ for $graphLookup
- All other phases: MongoDB 3.0+

### Quarkus Version
- Phase 5 requires @Transactional support
- All phases: Quarkus 3.x

### Morphia Version
- Phase 2 requires @Version support
- All phases: Morphia 2.x

## Quick Commands

### Check MongoDB Version
```bash
db.version()
```

### Verify Version Field
```bash
db.collection.find({version: {$exists: true}}).count()
```

### Test $graphLookup Support
```bash
db.collection.aggregate([{$graphLookup: {...}}])
```

### Monitor Version Conflicts
```bash
kubectl logs -f deployment/quantum-api | grep "VersionMismatch"
```

### Check Type Coercion Warnings
```bash
kubectl logs -f deployment/quantum-api | grep "Coercing"
```

## Emergency Contacts

- **Phase 1-2 Lead**: Security & Updates team
- **Phase 3-4 Lead**: API team
- **Phase 5 Lead**: Performance team + DBA
- **Phase 6 Lead**: Query optimization team

## Documentation Links

- [Full Roadmap](./IMPLEMENTATION_ROADMAP.md)
- [Detailed Plan](./SECURITY_PROVENANCE_UPDATES_PLAN.md)
- [Ontology Docs](./quantum-ontology-core/ONTOLOGY_VALIDATION.md)
- [Meta Scope](./quantum-ontology-core/ONTOLOGY_META_SCOPE.md)
