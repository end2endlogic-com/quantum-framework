# Ontology Validation and Reasoning

This document describes the validation, closure computation, hashing, and materialization features of the quantum-ontology framework.

## Validation

The `OntologyValidator` performs comprehensive validation of TBox definitions:

### Reference Integrity
- All property domains/ranges must reference existing classes
- All `inverseOf` references must point to existing properties
- All `subPropertyOf` references must point to existing properties
- All property chain members must reference existing properties

### Cycle Detection
- **Property Hierarchy**: Detects cycles in `subPropertyOf` relationships
- **Class Hierarchy**: Detects cycles in class parent relationships
- Throws `IllegalArgumentException` with clear message indicating the cycle

### Property Chain Validation
- Chains must have at least 2 properties
- All chain members must exist
- **Non-regular restriction**: Chains cannot involve transitive properties (either in chain or as implied property)
- This prevents undecidable reasoning scenarios

### Transitive Property Constraints
- Transitive properties must have same domain and range (when both are specified)

### YAML Error Reporting
When validation fails during YAML loading, errors include:
- Property/class name causing the issue
- Type of validation failure
- For YAML syntax errors, line/column information is provided by Jackson parser

Example error messages:
```
Unknown class 'InvalidClass' in domain of property 'hasParent'
Cycle detected in subPropertyOf hierarchy involving 'knows'
Property chain cannot contain transitive property 'ancestorOf' (non-regular)
Transitive property 'ancestorOf' must have same domain and range
```

## Closure Methods

The `OntologyRegistry` interface provides precomputed closure methods for efficient hierarchy navigation:

### Property Closures

```java
// Get all super-properties (transitive closure of subPropertyOf)
Set<String> supers = registry.superPropertiesOf("hasChild");
// Returns: ["hasDescendant", "relatedTo"]

// Get all sub-properties (inverse of subPropertyOf)
Set<String> subs = registry.subPropertiesOf("relatedTo");
// Returns: ["hasChild", "hasDescendant", "knows"]

// Get inverse property (bidirectional lookup)
Optional<String> inv = registry.inverseOf("hasChild");
// Returns: Optional["hasParent"]
```

### Class Closures

```java
// Get all ancestor classes (transitive closure of parents)
Set<String> ancestors = registry.ancestorsOf("Student");
// Returns: ["Person", "Agent", "Thing"]

// Get all descendant classes (inverse of parents)
Set<String> descendants = registry.descendantsOf("Person");
// Returns: ["Student", "Teacher", "Employee"]
```

### REST Endpoints

```bash
# Get super-properties
GET /ontology/properties/{name}/superProperties

# Get sub-properties
GET /ontology/properties/{name}/subProperties

# Get inverse property
GET /ontology/properties/{name}/inverse

# Get ancestor classes
GET /ontology/classes/{name}/ancestors

# Get descendant classes
GET /ontology/classes/{name}/descendants
```

## TBox Hashing

The framework computes stable SHA-256 hashes of TBox definitions for change detection:

### Canonicalization
- All maps sorted by keys
- All sets converted to sorted lists
- Consistent JSON serialization with `ORDER_MAP_ENTRIES_BY_KEYS`
- Includes classes, properties (with all attributes), and property chains

### Usage

```java
// Get TBox hash from registry
String hash = registry.getTBoxHash();

// Compute hash directly
String hash = TBoxHasher.computeHash(tbox);
```

### Persistence
- `yamlHash`: SHA-256 of raw YAML source file
- `tboxHash`: SHA-256 of canonicalized TBox structure
- Both stored in `OntologyMeta` collection
- Exposed via `/ontology/version` and `/ontology/hash` endpoints

### REST Endpoints

```bash
# Get current TBox hash
GET /ontology/hash
# Response: {"tboxHash": "a3f2b1..."}

# Get version metadata (includes both hashes)
GET /ontology/version
# Response: {"yamlHash": "...", "tboxHash": "...", "appliedAt": "2024-01-15T10:30:00Z"}
```

## Inferred Property Semantics

Properties can be marked as `inferred: true` in YAML to indicate they are computed/derived:

```yaml
properties:
  - id: ancestorOf
    domain: Person
    range: Person
    transitive: true
    inferred: false  # Direct relationship

  - id: descendantOf
    domain: Person
    range: Person
    inverseOf: ancestorOf
    inferred: true   # Computed from inverse
```

### Semantics
- **Read-only**: Inferred properties are marked as read-only in REST responses
- **Write ignored**: Attempts to write inferred properties are silently ignored
- **Visual distinction**: Graph visualizations show inferred properties with dashed lines
- **Automatic marking**: Properties implied by chains are automatically marked as inferred

### REST Response
```json
{
  "name": "descendantOf",
  "domain": "Person",
  "range": "Person",
  "inferred": true,
  "readOnly": true
}
```

### JointJS Graph
Inferred properties and edges are styled differently:
```json
{
  "type": "standard.Link",
  "attrs": {
    "line": {
      "strokeDasharray": "6,4",
      "stroke": "#9575cd"
    }
  },
  "data": {
    "inferred": true
  }
}
```

## Materialization Engine

The `MaterializationEngine` applies ontology rules to derive inferred edges using a fixpoint algorithm:

### Rules Applied
1. **SubPropertyOf**: If `(s, p, o)` and `p subPropertyOf q`, infer `(s, q, o)`
2. **Inverse**: If `(s, p, o)` and `p inverseOf q`, infer `(o, q, s)`
3. **Symmetric**: If `(s, p, o)` and `p symmetric`, infer `(o, p, s)`
4. **Transitive**: If `(s, p, o)` and `(o, p, t)` and `p transitive`, infer `(s, p, t)`
5. **Property Chains**: If chain `[p1, p2, ..., pn]` implies `q`, and path exists, infer `(s, q, o)`

### Fixpoint Iteration
```java
MaterializationEngine engine = new MaterializationEngine(registry, maxIterations);
Set<Edge> allEdges = engine.materialize(tenantId, entityId, entityType, explicitEdges);
```

- Iterates until no new edges are derived (fixpoint)
- Maximum iterations configurable (default: 10)
- Each iteration applies all rules to newly derived edges
- Returns combined set of explicit + inferred edges

### Provenance Tracking
All inferred edges include provenance:
```java
Edge inferredEdge = new Edge(
    srcId, srcType, predicate, dstId, dstType,
    true,  // inferred flag
    Optional.of(new Provenance("transitive", Map.of(
        "prop", "ancestorOf",
        "path", List.of("person1", "person2", "person3")
    )))
);
```

### Integration with PostPersistHook
The materialization engine can be safely consumed in post-persist hooks:

```java
@ApplicationScoped
public class OntologyPostPersistHook {
    @Inject MaterializationEngine engine;
    @Inject OntologyEdgeRepo edgeRepo;
    
    public void onEntitySaved(String tenantId, String entityId, String entityType) {
        List<Edge> explicit = edgeRepo.findBySrc(tenantId, entityId);
        Set<Edge> all = engine.materialize(tenantId, entityId, entityType, explicit);
        
        // Save only inferred edges
        all.stream()
           .filter(Edge::inferred)
           .forEach(e -> edgeRepo.saveInferred(tenantId, e));
    }
}
```

## Performance Considerations

### Closure Precomputation
- Closures can be precomputed at startup and cached
- Use `OntologyClosures` utility for on-demand computation
- Consider implementing caching decorator for `OntologyRegistry`

### Materialization Bounds
- Set appropriate `maxIterations` to prevent runaway computation
- Monitor edge count growth per iteration
- Consider incremental materialization for large graphs

### Hash Computation
- TBox hash computed once at load time
- Cached in `YamlBackedOntologyRegistry`
- Recomputed only when ontology changes

## Example: Complete Workflow

```java
// 1. Load and validate ontology
YamlOntologyLoader loader = new YamlOntologyLoader();
TBox tbox = loader.loadFromClasspath("/ontology.yaml");
// Validation happens automatically in loader

// 2. Create registry with hash
String yamlHash = computeYamlHash(yamlContent);
String tboxHash = TBoxHasher.computeHash(tbox);
OntologyRegistry registry = new YamlBackedOntologyRegistry(tbox, yamlHash, false);

// 3. Persist metadata
metaRepo.markApplied(yamlHash, tboxHash);

// 4. Use closures
Set<String> supers = registry.superPropertiesOf("hasChild");

// 5. Materialize inferred edges
MaterializationEngine engine = new MaterializationEngine(registry);
Set<Edge> allEdges = engine.materialize(tenantId, entityId, entityType, explicitEdges);
```
