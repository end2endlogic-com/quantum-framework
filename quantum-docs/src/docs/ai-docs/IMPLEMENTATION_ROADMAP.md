# Quantum Framework Enhancement Roadmap

## Executive Summary
Multi-phase implementation plan for security, provenance, updates, validation, and hierarchy optimizations. Each phase delivers production-ready, independently deployable features.

---

## Phase 1: Security & Provenance Foundation (Week 1-2)
**Goal**: Ensure all read operations set provenance and support anonymous access.

### Deliverables
1. **modelSourceRealm Helper** - Centralized setter for all read paths
2. **Anonymous Fallback** - Configurable security context for unauthenticated requests
3. **Unit Tests** - Verify provenance on all read operations

### Files Modified
- `MorphiaRepo.java` - Add `setSourceRealm()` helpers
- `MorphiaRepo.java` - Update `ensureSecurityContextFromIdentity()`
- `application.properties` - Add `quantum.security.allow-anonymous-fallback`

### Success Criteria
- ✓ All `findById`, `findByRefName`, `list`, `stream` set `modelSourceRealm`
- ✓ Anonymous requests succeed when config enabled
- ✓ 100% test coverage for provenance setting

### Risk: LOW
- Non-breaking changes
- Backward compatible
- Feature-flagged

---

## Phase 2: Update Safety & Integrity (Week 3-4)
**Goal**: Prevent concurrent update conflicts and enable type-safe updates.

### Deliverables
1. **Optimistic Locking** - Version-based conflict detection
2. **Type Coercion** - Safe type conversion with logging
3. **Update Result Types** - Structured responses for update operations

### Files Created
- `OptimisticUpdateResult.java` - Sealed interface for update outcomes
- `TypeCoercer.java` - Type conversion utility
- `UpdateManyResult.java` - Bulk update result wrapper

### Files Modified
- `UnversionedBaseModel.java` - Add `@Version Long version` field
- `MorphiaRepo.java` - Add `updateWithVersion()` method
- `MorphiaRepo.java` - Add `coerceUpdatePayload()` helper

### Success Criteria
- ✓ Version mismatch returns 409 with current version
- ✓ Type coercion logs all conversions at WARN level
- ✓ Clients can detect and retry conflicts

### Risk: MEDIUM
- Requires database migration for version field
- Clients must handle 409 responses
- Type coercion may mask bugs

---

## Phase 3: State Transition Validation (Week 5)
**Goal**: Optional validation of state machine transitions in bulk updates.

### Deliverables
1. **StateTransitionValidator Interface** - Pluggable validation
2. **updateManyWithValidation()** - Batch updates with state checks
3. **Documentation** - State machine patterns

### Files Created
- `StateTransitionValidator.java` - Validation interface
- `UpdateManyResult.java` - Enhanced with validation errors

### Files Modified
- `MorphiaRepo.java` - Add `updateManyWithValidation()` method
- Existing `updateMany()` - Add javadoc clarifying no validation

### Success Criteria
- ✓ Invalid transitions rejected with clear errors
- ✓ Valid entities updated, invalid skipped
- ✓ Grouped by current state for efficiency

### Risk: LOW
- Opt-in feature
- Existing updateMany unchanged
- Clear documentation

---

## Phase 4: DTO Pattern for Read Safety (Week 6)
**Goal**: Prevent accidental persistence of read-only fields.

### Deliverables
1. **EntityDTO** - Generic wrapper with metadata
2. **Resource Layer Updates** - Return DTOs from GET endpoints
3. **Serialization Tests** - Verify DTOs don't persist

### Files Created
- `EntityDTO.java` - Generic DTO wrapper
- `EntityDTOMapper.java` - Conversion utilities

### Files Modified
- Resource classes - Wrap responses in `EntityDTO.from()`

### Success Criteria
- ✓ GET endpoints return DTOs with sourceRealm
- ✓ DTOs cannot be accidentally persisted
- ✓ Backward compatible JSON structure

### Risk: LOW
- Additive changes only
- Clients see additional metadata
- Can be adopted incrementally

---

## Phase 5: Hierarchy Optimization (Week 7-8)
**Goal**: Eliminate N+1 queries and ensure transactional consistency.

### Deliverables
1. **Batched Subtree Fetch** - Single query for entire tree
2. **Transactional Child Operations** - Atomic parent/child updates
3. **MenuItemDTO** - Prevent persistence of filtered trees
4. **Field Rename** - `descendants` → `childrenIds`

### Files Created
- `MenuItemDTO.java` - Tree DTO structure
- `RenameDescendantsToChildrenIds.java` - Migration changeset

### Files Modified
- `MenuHierarchyRepo.java` - Replace recursive calls with `$graphLookup`
- `HierarchicalRepo.java` - Add `addChild()`, `removeChild()`
- `HierarchicalModel.java` - Deprecate `descendants`, add `childrenIds`

### Success Criteria
- ✓ getFilteredMenu: 1 query vs N queries
- ✓ Child operations atomic (both or neither)
- ✓ Filtered trees cannot be persisted
- ✓ Migration runs without downtime

### Risk: MEDIUM
- Database migration required
- Performance impact on large trees
- Requires MongoDB 3.4+ for $graphLookup

---

## Phase 6: StaticDynamicList Enhancement (Week 9)
**Goal**: Support multi-item filtering with any-match semantics.

### Deliverables
1. **Any-Match Query** - Filter by any array element
2. **Tests** - Verify multi-item scenarios
3. **Documentation** - Query patterns

### Files Modified
- `MenuItemStaticDynamicListRepo.java` - Update query to match any item

### Success Criteria
- ✓ Matches any item in list, not just first
- ✓ Backward compatible with single-item lists
- ✓ Performance equivalent to previous implementation

### Risk: LOW
- Simple query change
- MongoDB native array matching
- No schema changes

---

## Implementation Schedule

```
Week 1-2:  Phase 1 - Security & Provenance
Week 3-4:  Phase 2 - Update Safety
Week 5:    Phase 3 - State Validation
Week 6:    Phase 4 - DTO Pattern
Week 7-8:  Phase 5 - Hierarchy Optimization
Week 9:    Phase 6 - StaticDynamicList
Week 10:   Integration Testing & Documentation
```

---

## Configuration Strategy

### Phase 1
```properties
quantum.security.allow-anonymous-fallback=false
```

### Phase 2
```properties
quantum.updates.enable-type-coercion=true
quantum.updates.enable-optimistic-locking=true
```

### Phase 3
```properties
quantum.updates.validate-state-transitions=false
```

### Phase 5
```properties
quantum.hierarchy.use-batched-fetch=true
quantum.hierarchy.max-subtree-depth=10
```

---

## Testing Strategy

### Per-Phase Testing
- **Unit Tests**: Each phase includes focused unit tests
- **Integration Tests**: End-to-end scenarios per phase
- **Performance Tests**: Phases 2, 5 include benchmarks

### Regression Testing
- Full regression suite after each phase
- Backward compatibility verified
- Performance baseline maintained

---

## Rollback Plan

### Phase 1-4: Code-Only
- Revert deployment
- No database changes
- Zero downtime

### Phase 5: Requires Migration
- Keep deprecated `descendants` field for 1 release
- Dual-write to both fields during transition
- Drop old field after grace period

---

## Success Metrics

### Phase 1
- 0 instances of missing `modelSourceRealm` in logs
- Anonymous requests < 5% of total (if enabled)

### Phase 2
- Version conflicts < 1% of updates
- Type coercion warnings < 0.1% of updates

### Phase 3
- State validation errors < 2% of bulk updates

### Phase 5
- getFilteredMenu latency reduced by 80%
- Database query count reduced from N to 1

---

## Dependencies

### External
- MongoDB 3.4+ (for $graphLookup in Phase 5)
- Quarkus 3.x (for @Transactional in Phase 5)
- Morphia 2.x (for @Version in Phase 2)

### Internal
- Phase 2 depends on Phase 1 (security context)
- Phase 4 depends on Phase 1 (provenance)
- Phase 5 independent
- Phase 6 independent

---

## Risk Mitigation

### High-Risk Items
1. **Phase 2 - Version Field Migration**
   - Mitigation: Add field with default null, backfill gradually
   
2. **Phase 5 - Hierarchy Migration**
   - Mitigation: Dual-write period, feature flag for new queries

### Medium-Risk Items
1. **Phase 2 - Type Coercion**
   - Mitigation: Strict logging, opt-in via config
   
2. **Phase 5 - $graphLookup Performance**
   - Mitigation: Benchmark before rollout, add depth limit

---

## Documentation Deliverables

### Per-Phase
- API documentation updates
- Configuration guide
- Migration guide (if applicable)
- Example code snippets

### Final
- Complete feature guide
- Performance tuning guide
- Troubleshooting guide
- Upgrade guide

---

## Team Allocation

### Phase 1-2 (Critical Path)
- 2 developers
- 1 QA engineer
- Security review required

### Phase 3-4 (Parallel Track)
- 1 developer
- 1 QA engineer

### Phase 5 (Performance Focus)
- 2 developers
- 1 DBA for migration
- Performance testing required

### Phase 6 (Quick Win)
- 1 developer
- Minimal QA

---

## Go/No-Go Criteria

### Before Each Phase
- ✓ Previous phase deployed to production
- ✓ No P1/P2 bugs from previous phase
- ✓ Performance metrics within baseline
- ✓ Documentation complete

### Phase-Specific
- **Phase 2**: Version field migration tested in staging
- **Phase 5**: $graphLookup performance validated
- **Phase 5**: Rollback procedure tested

---

## Post-Implementation

### Monitoring
- Add metrics for each feature
- Alert on anomalies (version conflicts, coercion warnings)
- Dashboard for hierarchy query performance

### Optimization
- Phase 5: Index tuning for $graphLookup
- Phase 2: Analyze version conflict patterns
- Phase 3: Optimize state transition checks

### Future Enhancements
- Phase 2+: Automatic conflict resolution strategies
- Phase 5: Incremental tree loading for large hierarchies
- Phase 3: Visual state machine editor
