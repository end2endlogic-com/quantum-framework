# Ontology Edge Schema Improvements

## Overview

This document proposes improvements to the `OntologyEdge` collection schema to improve clarity, type safety, and MongoDB indexing efficiency.

## Current State

### OntologyEdge Model (`edges` collection)

```java
public class OntologyEdge extends UnversionedBaseModel {
    protected String src;       // Source entity identifier (ObjectId hex string)
    protected String srcType;   // Source entity class name
    protected String p;         // Predicate/edge type name
    protected String dst;       // Destination entity identifier (ObjectId hex string)
    protected String dstType;   // Destination entity class name
    protected boolean inferred; // True if edge was inferred by reasoner
    protected boolean derived;  // True if edge was computed by ComputedEdgeProvider
    protected Map<String, Object> prov;  // Provenance metadata
    protected List<Support> support;     // Support chain for derived edges
    protected Date ts;          // Timestamp
}
```

### Current Field Semantics

| Field | Current Name | Actual Content | Source |
|-------|--------------|----------------|--------|
| `src` | "source" | ObjectId hex string (e.g., `"507f1f77bcf86cd799439011"`) | `entity.getId().toString()` via `DefaultIdAccessor.idOf()` |
| `dst` | "destination" | ObjectId hex string | Same as src |
| `srcType` | "source type" | Class identifier (e.g., `"Associate"`, `"Location"`) | `@OntologyClass(id=...)` or `class.getSimpleName()` |
| `dstType` | "destination type" | Class identifier | Same as srcType |
| `p` | "predicate" | Edge type name (e.g., `"worksAtLocation"`, `"hasRole"`) | `@OntologyProperty(edgeType=...)` or field name |

### Data Flow

```
Entity Instance
     │
     ▼
AnnotatedEdgeExtractor.fromEntity()
     │
     ├─► DefaultIdAccessor.idOf(entity)
     │        │
     │        ├─► entity.getId().toString()  ──► src/dst (hex string)
     │        │
     │        └─► EntityReference.getEntityId().toHexString()
     │
     └─► extractor.metaOf(class).classId  ──► srcType/dstType
```

## Problems with Current Schema

### 1. Naming Ambiguity

- **`src`/`dst`**: These names don't indicate they contain ObjectId hex strings. Could be interpreted as refName, URL, or any identifier.
- **`srcType`/`dstType`**: "Type" is generic. These are specifically ontology class identifiers derived from `@OntologyClass`.
- **`p`**: Single-letter names are cryptic. This is specifically a predicate/property name.

### 2. Type Inefficiency

- **ObjectIds stored as Strings**: MongoDB indexes ObjectId types more efficiently than 24-character hex strings.
  - ObjectId: 12 bytes binary
  - Hex String: 24 bytes UTF-8 + string overhead
  - Index size ~2x larger with strings

### 3. Validation Difficulty

- No way to programmatically distinguish between:
  - A valid ObjectId hex string (`"507f1f77bcf86cd799439011"`)
  - A refName (`"john-doe"`)
  - An arbitrary string (`"test-123"`)

### 4. Cross-Reference Verification

- Hard to verify edges point to valid entities without knowing the collection name (derived from `srcType`/`dstType`)
- No foreign key constraint enforcement

## Proposed Schema Changes

### Option A: Full Rename + ObjectId Typing (Recommended Long-term)

```java
public class OntologyEdge extends UnversionedBaseModel {
    // Renamed for clarity - ObjectId type for efficient indexing
    @Property("srcId")
    protected ObjectId srcId;           // Source entity _id

    @Property("srcClassName")
    protected String srcClassName;      // Source ontology class (e.g., "Associate")

    @Property("predicate")
    protected String predicate;         // Edge type/property name (e.g., "worksAtLocation")

    @Property("dstId")
    protected ObjectId dstId;           // Destination entity _id

    @Property("dstClassName")
    protected String dstClassName;      // Destination ontology class (e.g., "Location")

    // Unchanged
    protected boolean inferred;
    protected boolean derived;
    protected Map<String, Object> prov;
    protected List<Support> support;
    protected Date ts;
}
```

**Migration Required:**
- Data migration script to convert hex strings to ObjectId
- Update all 50+ files referencing these fields
- Update indexes

### Option B: Rename Only (Keep String Type)

Same field renames as Option A, but keep `srcId`/`dstId` as `String`:

```java
protected String srcId;         // Hex string of source ObjectId
protected String srcClassName;  // Source ontology class
protected String predicate;     // Edge type name
protected String dstId;         // Hex string of destination ObjectId
protected String dstClassName;  // Destination ontology class
```

**Benefits:**
- Improves readability without data migration
- Can add ObjectId typing in a future phase

### Option C: Add Dual Fields with Morphia @AlsoLoad

Maintain backward compatibility while adding properly-typed fields:

```java
@AlsoLoad("src")
protected String srcId;

@AlsoLoad("srcType")
protected String srcClassName;

// New ObjectId fields for efficient queries (populated during write)
protected ObjectId srcObjectId;
protected ObjectId dstObjectId;
```

**Benefits:**
- Zero-downtime migration
- Old code continues to work
- New code can use properly-typed fields

## Affected Components

### Core Files (Must Update)

| File | Changes Needed |
|------|----------------|
| `OntologyEdge.java` | Field renames, type changes |
| `EdgeRecord.java` | Field renames |
| `Reasoner.Edge` | Record field renames |
| `OntologyEdgeRepo.java` | Query field names |
| `OntologyMaterializer.java` | Field access |
| `AnnotatedEdgeExtractor.java` | Edge construction |
| `ForwardChainingReasoner.java` | Edge field access |

### Test Files (58 total affected)

All integration and unit tests using edge queries or assertions.

### Indexes to Update

```java
@Indexes({
    @Index(options = @IndexOptions(name = "uniq_domain_src_p_dst", unique = true),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("srcId"),        // was: src
               @Field("predicate"),    // was: p
               @Field("dstId")         // was: dst
           }),
    // ... similar for other indexes
})
```

## Migration Strategy

### Phase 1: Preparation
1. Add JavaDoc to existing fields explaining their semantics
2. Create utility methods with descriptive names that wrap field access
3. Add validation to ensure src/dst contain valid ObjectId hex strings

### Phase 2: Dual-Write
1. Add new properly-named fields alongside old fields
2. Write to both old and new fields
3. Read from old fields (for backward compatibility)

### Phase 3: Migration
1. Run migration script to populate new fields from old data
2. Switch reads to new fields
3. Verify all systems working

### Phase 4: Cleanup
1. Remove old field writes
2. Remove @AlsoLoad annotations
3. Remove old fields from model

## Estimated Effort

| Phase | Effort | Risk |
|-------|--------|------|
| Phase 1 (Document) | 2-4 hours | Low |
| Phase 2 (Dual-Write) | 1-2 days | Medium |
| Phase 3 (Migration) | 1 day | Medium |
| Phase 4 (Cleanup) | 1 day | Low |

## Recommendation

For immediate action: **Phase 1 only** - Document current semantics clearly.

For future: **Option B (Rename Only)** provides the best balance of clarity improvement vs. migration effort. ObjectId typing (Option A) can be added later if performance profiling shows index size is a concern.

## Scalability Analysis

### Expected Scale

- **100,000+ edges** per tenant is realistic for enterprise deployments
- Each edge document: ~500 bytes (with dataDomain, provenance, support)
- Total collection size: 50-500 MB per tenant
- Index size critical for query performance

### Current Index Analysis

**Existing Indexes:**

| Index Name | Fields | Size Estimate (100K edges) |
|------------|--------|---------------------------|
| `_id` (default) | `_id` | ~2.4 MB |
| `uniq_domain_src_p_dst` | dataDomain.* + src + p + dst | ~15 MB |
| `idx_domain_p_dst` | dataDomain.* + p + dst | ~12 MB |
| `idx_domain_src_p` | dataDomain.* + src + p | ~12 MB |
| `idx_domain_derived` | dataDomain.* + derived | ~8 MB |

**Total estimated index overhead: ~50 MB per 100K edges**

### Query Pattern Coverage Analysis

| Query Pattern | Method | Index Used | Efficiency |
|--------------|--------|------------|------------|
| Find by src+p+dst | `upsert()` | uniq_domain_src_p_dst | ✅ Optimal |
| Find by src+p | `findBySrcAndP()` | idx_domain_src_p | ✅ Optimal |
| Find by p+dst | `findByDstAndP()` | idx_domain_p_dst | ✅ Optimal |
| Find by src only | `findBySrc()` | idx_domain_src_p (partial) | ⚠️ Suboptimal |
| Find by dst only | `findByDst()` | **NONE** | ❌ Collection scan |
| Find by p only | `findByProperty()` | idx_domain_p_dst (partial) | ⚠️ Suboptimal |
| Delete inferred by src+p | `deleteInferredBySrcNotIn()` | idx_domain_src_p + scan | ⚠️ Needs inferred flag |
| Delete derived by src+p | `deleteDerivedBySrcNotIn()` | idx_domain_src_p + scan | ⚠️ Needs derived flag |

### Missing Indexes (Critical for Scale)

#### 1. Index for `findByDst()` - Currently Collection Scan!

```java
@Index(options = @IndexOptions(name = "idx_domain_dst"),
       fields = {
           @Field("dataDomain.orgRefName"),
           @Field("dataDomain.accountNum"),
           @Field("dataDomain.tenantId"),
           @Field("dataDomain.dataSegment"),
           @Field("dst")
       })
```

**Impact:** `findByDst()` currently scans entire collection. At 100K edges, this is ~100K document scans per query.

#### 2. Compound Index with Inferred Flag

```java
@Index(options = @IndexOptions(name = "idx_domain_src_p_inferred"),
       fields = {
           @Field("dataDomain.orgRefName"),
           @Field("dataDomain.accountNum"),
           @Field("dataDomain.tenantId"),
           @Field("dataDomain.dataSegment"),
           @Field("src"),
           @Field("p"),
           @Field("inferred")
       })
```

**Impact:** `deleteInferredBySrcNotIn()` currently fetches all src+p edges then filters. With this index, MongoDB can filter at index level.

#### 3. Compound Index with Derived Flag

```java
@Index(options = @IndexOptions(name = "idx_domain_src_p_derived"),
       fields = {
           @Field("dataDomain.orgRefName"),
           @Field("dataDomain.accountNum"),
           @Field("dataDomain.tenantId"),
           @Field("dataDomain.dataSegment"),
           @Field("src"),
           @Field("p"),
           @Field("derived")
       })
```

**Impact:** Same as above for `deleteDerivedBySrcNotIn()`.

### String vs ObjectId: Index Size Impact

| Field Type | Storage per value | Index entry overhead | 100K edges index size |
|------------|-------------------|---------------------|----------------------|
| String (24 char hex) | 25 bytes | ~35 bytes | ~3.5 MB per field |
| ObjectId | 12 bytes | ~20 bytes | ~2.0 MB per field |

**Potential savings with ObjectId:** ~30-40% smaller indexes for src/dst fields.

For a compound index with src+dst:
- String: ~7 MB
- ObjectId: ~4 MB
- Savings: ~3 MB per index

With 4 indexes using src or dst, total savings: **~10-12 MB per 100K edges**

### Recommended Index Strategy

#### Immediate (Add Missing Indexes)

```java
@Indexes({
    // Existing indexes...

    // NEW: Critical for findByDst() - prevents collection scan
    @Index(options = @IndexOptions(name = "idx_domain_dst"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("dst")
           }),

    // NEW: Efficient inferred edge pruning
    @Index(options = @IndexOptions(name = "idx_domain_src_inferred"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("src"),
               @Field("inferred")
           })
})
```

#### Future (With ObjectId Migration)

Convert src/dst to ObjectId type for:
- 30-40% smaller indexes
- Faster comparisons (binary vs string)
- Native MongoDB $lookup support for graph queries

### Bulk Operations for Reindex Performance

Current reindex performs individual upserts. For 100K+ edges:

```java
// Current: Individual saves (slow)
for (edge : edges) {
    edgeRepo.save(edge);  // Network round-trip per edge
}

// Better: Bulk write (already implemented in bulkUpsertEdgeRecords)
edgeRepo.bulkUpsertEdgeRecords(edges);  // Single network round-trip
```

**Recommendation:** Ensure `OntologyMaterializer` uses `bulkUpsertEdgeRecords()` for batches, not individual `upsert()` calls.

### Monitoring Queries

To validate index usage at scale:

```javascript
// Check index usage stats
db.edges.aggregate([
  { $indexStats: {} }
])

// Explain a slow query
db.edges.find({
  "dataDomain.tenantId": "tenant-1",
  "dst": "507f1f77bcf86cd799439011"
}).explain("executionStats")

// Check collection stats
db.edges.stats()
```

## Appendix: Field Name Mapping Reference

| Old Name | New Name | Type | Description |
|----------|----------|------|-------------|
| `src` | `srcId` | String (or ObjectId) | Entity's MongoDB `_id` |
| `srcType` | `srcClassName` | String | `@OntologyClass.id()` or class simple name |
| `p` | `predicate` | String | `@OntologyProperty.edgeType()` or field name |
| `dst` | `dstId` | String (or ObjectId) | Target entity's MongoDB `_id` |
| `dstType` | `dstClassName` | String | Target's ontology class identifier |
