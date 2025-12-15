# Ontology TBox Quick Reference

## Validation Rules

### ✓ Valid Patterns
```yaml
properties:
  - id: hasChild
    domain: Person
    range: Person
    inverseOf: hasParent
    
  - id: ancestorOf
    domain: Person
    range: Person      # Same as domain (required for transitive)
    transitive: true
    
  - id: familyRelation
    subPropertyOf: [relatedTo]
    
chains:
  - chain: [hasParent, hasParent]
    implies: grandparentOf
```

### ✗ Invalid Patterns
```yaml
# Unknown class
- id: bad
  domain: NonExistent  # Error: Unknown class

# Cycle in hierarchy
- id: p1
  subPropertyOf: [p2]
- id: p2
  subPropertyOf: [p1]  # Error: Cycle detected

# Transitive with different domain/range
- id: bad
  domain: Person
  range: Organization
  transitive: true     # Error: Must have same domain and range

# Chain with transitive property
chains:
  - chain: [ancestorOf, knows]  # Error: ancestorOf is transitive (non-regular)
    implies: related
```

## Closure Methods

```java
// Property hierarchy
Set<String> supers = registry.superPropertiesOf("hasChild");
Set<String> subs = registry.subPropertiesOf("relatedTo");
Optional<String> inv = registry.inverseOf("hasChild");

// Class hierarchy
Set<String> ancestors = registry.ancestorsOf("Student");
Set<String> descendants = registry.descendantsOf("Person");
```

## REST Endpoints

```bash
# Closures
GET /ontology/properties/{name}/superProperties
GET /ontology/properties/{name}/subProperties
GET /ontology/properties/{name}/inverse
GET /ontology/classes/{name}/ancestors
GET /ontology/classes/{name}/descendants

# Hashing
GET /ontology/hash
GET /ontology/version
```

## TBox Hashing

```java
// Compute hash
String hash = TBoxHasher.computeHash(tbox);
String hash = registry.getTBoxHash();

// Mark as applied (atomic, race-free)
metaRepo.markApplied(yamlHash, tboxHash, yamlVersion);

// Record observation without applying
metaRepo.upsertObservation(yamlVersion, source, reindexRequired);

// Check for changes
String current = registry.getTBoxHash();
String stored = metaRepo.getSingleton()
    .map(OntologyMeta::getTboxHash)
    .orElse(null);
boolean changed = !current.equals(stored);
```

**Note**: OntologyMeta is GLOBAL (not per-realm). TBox shared across all realms.

## Materialization

```java
// Create engine
MaterializationEngine engine = new MaterializationEngine(registry, 10);

// Materialize edges
Set<Edge> allEdges = engine.materialize(
    tenantId, 
    entityId, 
    entityType, 
    explicitEdges
);

// Filter inferred edges
List<Edge> inferred = allEdges.stream()
    .filter(Edge::inferred)
    .collect(Collectors.toList());
```

## Inferred Properties

```yaml
properties:
  - id: hasParent
    domain: Person
    range: Person
    inverseOf: hasChild
    inferred: true      # Computed from inverse
    
  - id: grandparentOf
    domain: Person
    range: Person
    inferred: true      # Implied by chain
```

**Semantics**:
- Read-only in REST responses
- Ignored on write operations
- Visually distinct in graphs (dashed lines)
- Automatically marked for chain-implied properties

## Error Messages

```
Unknown class 'Person' in domain of property 'hasChild'
Unknown property 'knows' in inverseOf of property 'friendOf'
Unknown property 'p1' in chain for implies 'result'
Cycle detected in subPropertyOf hierarchy involving 'knows'
Cycle detected in class hierarchy involving 'Person'
Property chain must have at least 2 properties
Property chain cannot contain transitive property 'ancestorOf' (non-regular)
Property chain cannot imply transitive property 'ancestorOf' (non-regular)
Transitive property 'ancestorOf' must have same domain and range
```

## Common Patterns

### Define Property Hierarchy
```yaml
properties:
  - id: relatedTo
    domain: Person
    range: Person
    
  - id: familyRelation
    domain: Person
    range: Person
    subPropertyOf: [relatedTo]
    
  - id: hasChild
    domain: Person
    range: Person
    subPropertyOf: [familyRelation]
```

### Define Inverse Pair
```yaml
properties:
  - id: hasChild
    domain: Person
    range: Person
    inverseOf: hasParent
    
  - id: hasParent
    domain: Person
    range: Person
    inverseOf: hasChild
    inferred: true
```

### Define Property Chain
```yaml
properties:
  - id: hasParent
    domain: Person
    range: Person
    
  - id: grandparentOf
    domain: Person
    range: Person
    inferred: true

chains:
  - chain: [hasParent, hasParent]
    implies: grandparentOf
```

### Define Transitive Property
```yaml
properties:
  - id: ancestorOf
    domain: Person
    range: Person
    transitive: true
```

## Integration with PostPersistHook

```java
@ApplicationScoped
public class OntologyPostPersistHook {
    @Inject OntologyRegistry registry;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject MaterializationEngine engine;
    
    public void onEntitySaved(String tenantId, String entityId, String entityType) {
        // Load explicit edges
        List<Edge> explicit = edgeRepo.findBySrc(tenantId, entityId);
        
        // Materialize inferred edges
        Set<Edge> all = engine.materialize(tenantId, entityId, entityType, explicit);
        
        // Save only new inferred edges
        all.stream()
           .filter(Edge::inferred)
           .forEach(e -> edgeRepo.saveInferred(tenantId, e));
    }
}
```

## Performance Tips

1. **Cache closures** for frequently accessed properties/classes
2. **Set maxIterations** appropriately for your graph size
3. **Monitor edge growth** during materialization
4. **Precompute closures** at startup for large ontologies
5. **Use incremental materialization** for large graphs
