# Ontology TBox Implementation Summary

This document summarizes the implementation of validation, closures, hashing, and materialization features for the quantum-ontology framework.

## Components Implemented

### 1. Enhanced Validation (OntologyValidator)
**File**: `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/OntologyValidator.java`

**Features**:
- Reference integrity checks for all property/class references
- Cycle detection in property hierarchy (subPropertyOf)
- Cycle detection in class hierarchy (parents)
- Property chain validation:
  - Minimum 2 properties required
  - All chain members must exist
  - Rejects chains involving transitive properties (non-regular)
- Transitive property constraints (domain == range)
- Clear error messages with property/class names

**Error Examples**:
```
Unknown class 'InvalidClass' in domain of property 'hasParent'
Cycle detected in subPropertyOf hierarchy involving 'knows'
Property chain cannot contain transitive property 'ancestorOf' (non-regular)
```

### 2. Closure Methods (OntologyClosures)
**File**: `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/OntologyClosures.java`

**Methods**:
- `computeSuperProperties(propertyName, registry)` - Transitive closure of subPropertyOf
- `computeSubProperties(propertyName, registry)` - Inverse of subPropertyOf
- `computeAncestors(className, registry)` - Transitive closure of class parents
- `computeDescendants(className, registry)` - Inverse of class parents
- `computeInverse(propertyName, registry)` - Bidirectional inverse lookup

**Integration**: Added default methods to `OntologyRegistry` interface for easy access.

### 3. TBox Hashing (TBoxHasher)
**File**: `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/TBoxHasher.java`

**Features**:
- Computes stable SHA-256 hash of TBox
- Canonicalization: sorted keys, deterministic JSON
- Includes all classes, properties (with all attributes), and chains
- Independent of map insertion order

**Usage**:
```java
String hash = TBoxHasher.computeHash(tbox);
String hash = registry.getTBoxHash(); // via interface
```

### 4. Materialization Engine (MaterializationEngine)
**File**: `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/MaterializationEngine.java`

**Features**:
- Fixpoint algorithm for deriving inferred edges
- Rules applied:
  1. SubPropertyOf
  2. Inverse properties
  3. Symmetric properties
  4. Transitive closure
  5. Property chains
- Provenance tracking for all inferred edges
- Configurable max iterations (default: 10)

**Usage**:
```java
MaterializationEngine engine = new MaterializationEngine(registry);
Set<Edge> allEdges = engine.materialize(tenantId, entityId, entityType, explicitEdges);
```

### 5. REST Endpoints
**File**: `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/resource/OntologyResource.java`

**New Endpoints**:
```
GET /ontology/hash
GET /ontology/version (updated to include tboxHash)
GET /ontology/properties/{name}/superProperties
GET /ontology/properties/{name}/subProperties
GET /ontology/properties/{name}/inverse
GET /ontology/classes/{name}/ancestors
GET /ontology/classes/{name}/descendants
```

### 6. Model Updates
**Files**: 
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/model/OntologyMeta.java`
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/repo/OntologyMetaRepo.java`

**Changes**:
- Added `tboxHash` field to OntologyMeta
- Added unique index on `refName` to enforce singleton
- Documented as GLOBAL (not per-realm) - TBox shared across all realms
- Replaced save operations with `findAndModify` + `upsert=true` for race-free updates
- Updated `markApplied(yamlHash, tboxHash, yamlVersion)` to persist all three fields atomically
- Updated `upsertObservation` to use atomic operations

### 7. Inferred Property Semantics
**Implementation**:
- PropertyDef already has `inferred` boolean field
- YamlOntologyLoader reads `inferred: true` from YAML
- OntologyResource marks chain-implied properties as inferred
- Graph visualization shows inferred properties with dashed lines
- REST responses include `inferred` flag

## Tests

### Unit Tests Created
1. **OntologyValidatorTest** - Tests all validation scenarios
2. **OntologyClosuresTest** - Tests all closure computations
3. **TBoxHasherTest** - Tests hash stability and uniqueness

### Test Coverage
- Valid ontology passes validation
- Unknown class/property references detected
- Cycles in hierarchies detected
- Transitive property constraints enforced
- Property chain validation (length, transitive restrictions)
- Closure methods compute correct transitive closures
- Hash stability and independence from map order
- Hash uniqueness for different TBoxes

## Documentation

### Files Created
1. **ONTOLOGY_VALIDATION.md** - Comprehensive guide covering:
   - Validation rules and error messages
   - Closure method usage and examples
   - TBox hashing and canonicalization
   - Inferred property semantics
   - Materialization engine and fixpoint algorithm
   - Integration patterns (PostPersistHook)
   - Performance considerations

2. **ontology-validation-example.yaml** - Example YAML showing:
   - Valid property definitions
   - Inverse properties
   - Transitive properties
   - SubPropertyOf hierarchies
   - Property chains
   - Commented examples of invalid patterns

3. **README.md** (updated) - Added "Advanced Features" section

## Integration Points

### For Application Developers

**1. Validation happens automatically**:
```java
YamlOntologyLoader loader = new YamlOntologyLoader();
TBox tbox = loader.loadFromClasspath("/ontology.yaml");
// Validation runs automatically, throws IllegalArgumentException on error
```

**2. Use closure methods**:
```java
Set<String> supers = registry.superPropertiesOf("hasChild");
Set<String> ancestors = registry.ancestorsOf("Student");
Optional<String> inverse = registry.inverseOf("hasChild");
```

**3. Check TBox changes**:
```java
String currentHash = registry.getTBoxHash();
String storedHash = metaRepo.getSingleton().map(OntologyMeta::getTboxHash).orElse(null);
boolean changed = !currentHash.equals(storedHash);
```

**4. Materialize inferred edges**:
```java
MaterializationEngine engine = new MaterializationEngine(registry);
Set<Edge> allEdges = engine.materialize(tenantId, entityId, entityType, explicitEdges);

// Save only inferred edges
allEdges.stream()
    .filter(Edge::inferred)
    .forEach(e -> edgeRepo.saveInferred(tenantId, e));
```

### For REST API Consumers

**Query closures**:
```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/ontology/properties/hasChild/superProperties
```

**Check TBox hash**:
```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/ontology/hash
```

**Visualize inferred properties**:
- Inferred properties shown with dashed lines in JointJS graphs
- `data.inferred` field available for custom styling

## Backward Compatibility

All changes maintain backward compatibility:
- New methods in `OntologyRegistry` have default implementations
- PropertyDef has backward-compatible constructor without `inferred` field
- Existing REST endpoints unchanged
- New endpoints are additive

## Performance Notes

1. **Validation**: Runs once at load time, O(V+E) for cycle detection
2. **Closures**: Computed on-demand, consider caching for large ontologies
3. **Hashing**: Computed once at load, cached in registry
4. **Materialization**: Bounded by maxIterations, monitor edge growth

## Future Enhancements

Potential improvements:
1. Precompute and cache closures at startup
2. Incremental materialization for large graphs
3. Parallel materialization for independent subgraphs
4. YAML line/column tracking for better error messages
5. Visualization of validation errors in graph UI
