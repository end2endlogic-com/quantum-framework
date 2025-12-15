# OntologyMeta Scope and Concurrency

## Scope: Global vs Per-Realm

### TBox (Schema) - GLOBAL
The ontology TBox (classes, properties, chains) is **global** and shared across all realms:
- One `OntologyMeta` singleton per deployment
- Singleton enforced by unique index on `refName="global"`
- All realms use the same ontology schema

### ABox (Instances) - PER-REALM
Instance data (edges, entities) is **realm-scoped**:
- `OntologyEdge` collection includes `tenantId` field
- Each realm has its own instance graph
- Queries require `X-Realm` header

### Rationale
- Schema definitions are typically uniform across tenants
- Instance data must be isolated for multi-tenancy
- Simplifies ontology versioning and updates

## Concurrency Safety

### Race-Free Upserts
Both `upsertObservation` and `markApplied` use Morphia's `findAndModify` with `upsert=true`:

```java
ds().find(OntologyMeta.class)
    .filter(Filters.eq("refName", "global"))
    .modify(UpdateOperators.set("yamlHash", yamlHash), ...)
    .execute(new ModifyOptions()
        .upsert(true)
        .returnDocument(ReturnDocument.AFTER));
```

**Benefits**:
- Atomic operation (no read-modify-write race)
- Creates document if missing (upsert)
- Returns updated document
- Safe for concurrent deployments/restarts

### Unique Index Enforcement
```java
@Index(options = @IndexOptions(name = "idx_meta_singleton", unique = true), 
       fields = { @Field("refName") })
```

**Guarantees**:
- Only one document with `refName="global"` can exist
- MongoDB enforces at database level
- Prevents duplicate singletons even under concurrent writes

## Field Semantics

### Observation vs Applied
- **Observation**: YAML file detected/parsed (may not be applied yet)
  - `yamlVersion`, `source`, `updatedAt` updated via `upsertObservation`
  - Does NOT change `yamlHash`, `tboxHash`, `appliedAt`
  
- **Applied**: Ontology loaded into runtime and active
  - `yamlHash`, `tboxHash`, `yamlVersion`, `appliedAt` updated via `markApplied`
  - Clears `reindexRequired` flag

### Field Descriptions
| Field | Type | Meaning | Updated By |
|-------|------|---------|------------|
| `yamlHash` | String | SHA-256 of YAML source (applied) | `markApplied` |
| `tboxHash` | String | SHA-256 of canonicalized TBox (applied) | `markApplied` |
| `yamlVersion` | Integer | Version from YAML (applied) | `markApplied` |
| `source` | String | File path or classpath (observed) | `upsertObservation` |
| `updatedAt` | Date | Last observation time | Both |
| `appliedAt` | Date | When hashes were applied | `markApplied` |
| `reindexRequired` | boolean | Edges need recomputation | `upsertObservation` (set), `markApplied` (clear) |

## Usage Patterns

### Startup: Load and Apply Ontology
```java
// 1. Load YAML
YamlOntologyLoader loader = new YamlOntologyLoader();
TBox tbox = loader.loadFromClasspath("/ontology.yaml");

// 2. Compute hashes
String yamlHash = computeYamlHash(yamlContent);
String tboxHash = TBoxHasher.computeHash(tbox);
Integer yamlVersion = extractVersion(yamlContent);

// 3. Mark as applied (atomic upsert)
metaRepo.markApplied(yamlHash, tboxHash, yamlVersion);
```

### Runtime: Check for Changes
```java
Optional<OntologyMeta> meta = metaRepo.getSingleton();
String currentTBoxHash = registry.getTBoxHash();

boolean changed = meta.map(m -> !currentTBoxHash.equals(m.getTboxHash()))
                      .orElse(true);

if (changed) {
    // Trigger reindex
    meta.ifPresent(m -> metaRepo.upsertObservation(
        m.getYamlVersion(), 
        m.getSource(), 
        true  // reindexRequired
    ));
}
```

### Hot Reload: Observe New YAML
```java
// Detect new YAML file
String newYamlHash = computeYamlHash(newContent);
Optional<OntologyMeta> meta = metaRepo.getSingleton();

boolean yamlChanged = meta.map(m -> !newYamlHash.equals(m.getYamlHash()))
                          .orElse(true);

if (yamlChanged) {
    // Record observation (doesn't apply yet)
    metaRepo.upsertObservation(newVersion, newSource, true);
    
    // Later: load and apply
    TBox newTBox = loader.load(newContent);
    String newTBoxHash = TBoxHasher.computeHash(newTBox);
    metaRepo.markApplied(newYamlHash, newTBoxHash, newVersion);
}
```

## Multi-Deployment Scenarios

### Scenario 1: Rolling Deployment
- Pod A starts, loads ontology, calls `markApplied`
- Pod B starts concurrently, loads same ontology, calls `markApplied`
- **Result**: Both succeed (idempotent), last write wins, both pods have same TBox

### Scenario 2: Version Mismatch
- Pod A has ontology v1, Pod B has ontology v2
- Both call `markApplied` with different hashes
- **Result**: Last write wins, but `tboxHash` mismatch detected
- **Action**: Trigger reindex or alert on version skew

### Scenario 3: Concurrent Observation
- File watcher detects YAML change
- Multiple pods call `upsertObservation` concurrently
- **Result**: All succeed (atomic), `reindexRequired=true` set
- **Action**: Admin triggers reindex once

## Migration from Old Code

### Before (Race Condition)
```java
OntologyMeta meta = getSingleton().orElseGet(() -> {
    OntologyMeta m = new OntologyMeta();
    m.setRefName("global");
    return m;
});
meta.setYamlHash(yamlHash);
save(ds(), meta);  // Race: two pods could create duplicates
```

### After (Race-Free)
```java
metaRepo.markApplied(yamlHash, tboxHash, yamlVersion);
// Atomic findAndModify with upsert=true
```

## Monitoring and Alerts

### Recommended Checks
1. **Version Skew**: Alert if pods report different `tboxHash`
2. **Reindex Pending**: Monitor `reindexRequired` flag
3. **Stale Applied**: Alert if `appliedAt` is too old vs `updatedAt`
4. **Singleton Violation**: Should never happen (unique index), but monitor collection count

### Metrics
```java
// Expose via /q/metrics
@Gauge(name = "ontology_version", description = "Current ontology version")
public int getOntologyVersion() {
    return metaRepo.getSingleton()
        .map(OntologyMeta::getYamlVersion)
        .orElse(0);
}

@Gauge(name = "ontology_reindex_required", description = "Reindex required flag")
public int getReindexRequired() {
    return metaRepo.getSingleton()
        .map(m -> m.isReindexRequired() ? 1 : 0)
        .orElse(0);
}
```
