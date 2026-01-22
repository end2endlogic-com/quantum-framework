# Implementing Territory → Location Edge Materialization

This guide provides step-by-step prompts and instructions for implementing the `Associate → Territory → Location` relationship pattern using the Quantum Framework's ontology system.

## Overview

The goal is to enable queries like "What locations can Associate X access?" where:
- Associates are directly assigned to Territories
- Territories form a hierarchy (parent-child relationships)
- Each Territory has a `StaticDynamicList<Location>` that resolves to locations
- Associates should have access to locations from their assigned territories AND all descendant territories

### Architecture

```
Associate -[@OntologyProperty]-> Territory (direct assignment)
Territory -[HierarchyListEdgeProvider]-> Location (materialized via provider)
Associate -[Property Chain]-> Location (inferred by reasoner)
```

---

## Step 1: Ensure Domain Models Have Required Annotations

### Prompt for Claude:

```
Review my domain models and ensure they have the correct ontology annotations:

1. **Associate** (or equivalent user/entity class):
   - Must have `@OntologyClass` annotation
   - Must have `@OntologyProperty` on the field that references territories
   - The territory reference field should use `predicate = "hasTerritory"`

2. **Territory** (hierarchy node):
   - Must have `@OntologyClass` annotation
   - Must extend or implement the hierarchy pattern (HierarchicalModel or similar)
   - The `StaticDynamicList<Location>` field does NOT need @OntologyProperty (the provider handles this)

3. **Location** (target entity):
   - Must have `@OntologyClass` annotation

Please review these classes and show me what annotations need to be added or modified.
```

### Expected Model Structure:

```java
@OntologyClass(id = "Associate")
public class Associate extends BaseModel {

    @OntologyProperty(predicate = "hasTerritory", target = Territory.class)
    private List<EntityReference> territories;

    // ... other fields
}

@OntologyClass(id = "Territory")
public class Territory extends HierarchicalModel<Territory, Location, LocationList> {

    // StaticDynamicList is inherited from HierarchicalModel
    // NO @OntologyProperty needed here - the provider materializes these edges

    // ... other fields
}

@OntologyClass(id = "Location")
public class Location extends BaseModel {
    // ... fields
}
```

---

## Step 2: Implement the TerritoryLocationEdgeProvider

### Prompt for Claude:

```
Create a HierarchyListEdgeProvider implementation that materializes Territory → Location edges.

Requirements:
1. Create `TerritoryLocationEdgeProvider` extending `HierarchyListEdgeProvider<Territory, Territory, Location>`
2. The provider should:
   - Use source type `Territory.class`
   - Use predicate `"hasLocation"`
   - Use target type name `"Location"`
   - Use hierarchy type name `"Territory"`
3. Inject `HierarchicalRepo<Territory, Location, LocationList>` (or your specific territory repo)
4. Inject the list resolver service to resolve StaticDynamicList items
5. Implement:
   - `getAssignedNodeIds()` - return the territory's own ID (self-reference since Territory IS the hierarchy node)
   - `getHierarchyNode()` - load a Territory by ID
   - `getChildNodes()` - use the hierarchical repo to get all descendant territories
   - `resolveListItems()` - resolve the territory's StaticDynamicList to actual Location entities
   - `extractTargetId()` - extract the Location's refName or ID
   - `getDependencyTypes()` - return Set.of(Territory.class, LocationList.class)
   - `getAffectedSourceIds()` - query to find which territories are affected when a dependency changes

The provider must be annotated with `@ApplicationScoped` for CDI discovery.
```

### Example Implementation:

```java
package com.yourapp.ontology;

import com.e2eq.ontology.core.HierarchyListEdgeProvider;
import com.e2eq.ontology.core.ComputedEdgeProvider.ComputationContext;
import com.yourapp.model.Territory;
import com.yourapp.model.Location;
import com.yourapp.model.LocationList;
import com.yourapp.repo.TerritoryRepo;
import com.yourapp.repo.LocationListResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class TerritoryLocationEdgeProvider
        extends HierarchyListEdgeProvider<Territory, Territory, Location> {

    @Inject
    TerritoryRepo territoryRepo;

    @Inject
    LocationListResolver listResolver;

    @Override
    public Class<Territory> getSourceType() {
        return Territory.class;
    }

    @Override
    public String getPredicate() {
        return "hasLocation";
    }

    @Override
    public String getTargetTypeName() {
        return "Location";
    }

    @Override
    public String getHierarchyTypeName() {
        return "Territory";
    }

    @Override
    protected List<String> getAssignedNodeIds(Territory source) {
        // Territory IS the hierarchy node, so return its own ID
        return List.of(source.getId().toString());
    }

    @Override
    protected Territory getHierarchyNode(ComputationContext context, String nodeId) {
        return territoryRepo.findById(nodeId, context.dataDomainInfo());
    }

    @Override
    protected List<Territory> getChildNodes(ComputationContext context, String nodeId) {
        Territory parent = territoryRepo.findById(nodeId, context.dataDomainInfo());
        if (parent == null) return List.of();
        return territoryRepo.getAllChildren(context.dataDomainInfo(), parent);
    }

    @Override
    protected List<Location> resolveListItems(ComputationContext context, Territory node) {
        if (node.getStaticDynamicList() == null) {
            return List.of();
        }
        return listResolver.resolve(context.dataDomainInfo(), node.getStaticDynamicList());
    }

    @Override
    protected String extractTargetId(Location target) {
        return target.getRefName(); // or target.getId().toString()
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        return Set.of(Territory.class, LocationList.class, Location.class);
    }

    @Override
    public Set<String> getAffectedSourceIds(
            ComputationContext context,
            Class<?> dependencyType,
            String dependencyId) {

        if (Territory.class.isAssignableFrom(dependencyType)) {
            // When a territory changes, it and all ancestors are affected
            Set<String> affected = new HashSet<>();
            affected.add(dependencyId);

            // Also add ancestors since their computed edges include this territory's locations
            Territory t = territoryRepo.findById(dependencyId, context.dataDomainInfo());
            while (t != null && t.getParent() != null) {
                affected.add(t.getParent().getRefName());
                t = territoryRepo.findById(t.getParent().getRefName(), context.dataDomainInfo());
            }
            return affected;
        }

        if (Location.class.isAssignableFrom(dependencyType)) {
            // When a location changes, find all territories that include it
            return territoryRepo.findTerritoriesWithLocation(
                context.dataDomainInfo(),
                dependencyId
            );
        }

        return Set.of();
    }
}
```

---

## Step 3: Implement the AssociateLocationEdgeProvider (Optional Direct Approach)

If you want Associate → Location edges computed directly (without property chains), use this prompt:

### Prompt for Claude:

```
Create a HierarchyListEdgeProvider implementation that materializes Associate → Location edges directly.

Requirements:
1. Create `AssociateLocationEdgeProvider` extending `HierarchyListEdgeProvider<Associate, Territory, Location>`
2. The provider should:
   - Use source type `Associate.class`
   - Use predicate `"canAccessLocation"`
   - Use target type name `"Location"`
   - Use hierarchy type name `"Territory"`
3. Inject the territory repository and list resolver
4. Implement:
   - `getAssignedNodeIds()` - extract territory IDs from the Associate's territory references
   - `getHierarchyNode()` - load a Territory by ID
   - `getChildNodes()` - get all descendant territories
   - `resolveListItems()` - resolve each territory's StaticDynamicList
   - `extractTargetId()` - extract Location ID
   - `getDependencyTypes()` - return Set.of(Territory.class, LocationList.class, Location.class)
```

### Example Implementation:

```java
@ApplicationScoped
public class AssociateLocationEdgeProvider
        extends HierarchyListEdgeProvider<Associate, Territory, Location> {

    @Inject TerritoryRepo territoryRepo;
    @Inject LocationListResolver listResolver;

    @Override
    public Class<Associate> getSourceType() {
        return Associate.class;
    }

    @Override
    public String getPredicate() {
        return "canAccessLocation";
    }

    @Override
    public String getTargetTypeName() {
        return "Location";
    }

    @Override
    public String getHierarchyTypeName() {
        return "Territory";
    }

    @Override
    protected List<String> getAssignedNodeIds(Associate source) {
        if (source.getTerritories() == null) return List.of();
        return source.getTerritories().stream()
            .map(ref -> ref.getRefName())
            .filter(Objects::nonNull)
            .toList();
    }

    @Override
    protected Territory getHierarchyNode(ComputationContext context, String nodeId) {
        return territoryRepo.findByRefName(nodeId, context.dataDomainInfo());
    }

    @Override
    protected List<Territory> getChildNodes(ComputationContext context, String nodeId) {
        Territory parent = territoryRepo.findByRefName(nodeId, context.dataDomainInfo());
        if (parent == null) return List.of();
        return territoryRepo.getAllChildren(context.dataDomainInfo(), parent);
    }

    @Override
    protected List<Location> resolveListItems(ComputationContext context, Territory node) {
        if (node.getStaticDynamicList() == null) return List.of();
        return listResolver.resolve(context.dataDomainInfo(), node.getStaticDynamicList());
    }

    @Override
    protected String extractTargetId(Location target) {
        return target.getRefName();
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        return Set.of(Territory.class, LocationList.class, Location.class);
    }

    @Override
    public Set<String> getAffectedSourceIds(
            ComputationContext context,
            Class<?> dependencyType,
            String dependencyId) {

        if (Territory.class.isAssignableFrom(dependencyType)) {
            // Find all associates assigned to this territory or its ancestors
            return associateRepo.findByTerritoryOrAncestor(
                context.dataDomainInfo(),
                dependencyId
            );
        }
        return Set.of();
    }
}
```

---

## Step 4: Configure Property Chains (If Using Two-Phase Approach)

If you implemented `TerritoryLocationEdgeProvider` (Territory → Location) and want to use property chains for Associate → Location:

### Prompt for Claude:

```
Update my ontology configuration (YAML or Java) to add a property chain that infers Associate → Location from Associate → Territory → Location.

The chain should be:
- chain: [hasTerritory, hasLocation]
- implies: canAccessLocation

Show me where to add this configuration and the exact syntax.
```

### Example YAML Configuration:

```yaml
# ontology-config.yaml
ontology:
  properties:
    - id: hasTerritory
      domain: Associate
      range: Territory
    - id: hasLocation
      domain: Territory
      range: Location
    - id: canAccessLocation
      domain: Associate
      range: Location

  chains:
    - chain: [hasTerritory, hasLocation]
      implies: canAccessLocation
```

### Example Java Configuration:

```java
@ApplicationScoped
public class OntologyConfigProvider {

    @Produces
    @ApplicationScoped
    public OntologyRegistry createRegistry() {
        return OntologyRegistry.builder()
            .property("hasTerritory", "Associate", "Territory")
            .property("hasLocation", "Territory", "Location")
            .property("canAccessLocation", "Associate", "Location")
            .chain(List.of("hasTerritory", "hasLocation"), "canAccessLocation")
            .build();
    }
}
```

---

## Step 5: Add Bidirectional Edges (Optional)

If you need Location → Territory edges (inverse relationship):

### Prompt for Claude:

```
Create a provider or update the existing TerritoryLocationEdgeProvider to also create inverse edges from Location → Territory.

Options:
1. Add inverse edge creation in the existing provider
2. Create a separate LocationTerritoryEdgeProvider
3. Use @OntologyProperty with inverse configuration

Which approach is best for my use case, and implement it.
```

---

## Step 6: Testing the Implementation

### Prompt for Claude:

```
Create integration tests for the Territory → Location edge materialization:

1. Test that saving a Territory creates hasLocation edges to all resolved locations
2. Test that hierarchy traversal works (parent territory's edges include child territory's locations)
3. Test that provenance is correctly recorded (which territories and lists contributed)
4. Test that updating a territory's list updates the edges
5. Test that the property chain correctly infers Associate → Location edges
6. Test DataDomain scoping (edges are isolated per tenant)

Use the existing test patterns in quantum-ontology-mongo for reference.
```

---

## Step 7: Verify Auto-Discovery

After implementing, verify that your provider is auto-discovered:

### Prompt for Claude:

```
Help me verify that my TerritoryLocationEdgeProvider is being auto-discovered and registered:

1. Add logging to confirm registration at startup
2. Show me how to query the ComputedEdgeRegistry to see registered providers
3. Create a health check endpoint that shows provider status
```

### Verification Code:

```java
@ApplicationScoped
@Startup
public class ProviderHealthCheck {

    @Inject
    ComputedEdgeRegistry registry;

    @PostConstruct
    void logRegisteredProviders() {
        Log.info("=== Registered ComputedEdgeProviders ===");
        for (var provider : registry.getAllProviders()) {
            Log.infof("  - %s (source: %s, predicate: %s)",
                provider.getProviderId(),
                provider.getSourceTypeName(),
                provider.getPredicate());
        }
        Log.infof("Total: %d providers", registry.getAllProviders().size());
    }
}
```

---

## Troubleshooting

### Provider Not Being Called

**Prompt:**
```
My TerritoryLocationEdgeProvider is not being called when I save a Territory. Help me debug:

1. Is the provider registered? (Check ComputedEdgeRegistry)
2. Does Territory have @OntologyClass annotation?
3. Is OntologyWriteHook configured as a PostPersistHook?
4. Are there any errors in the logs during startup?
```

### Edges Not Being Created

**Prompt:**
```
The provider is being called but no edges appear in the ontology_edges collection. Debug:

1. Is the provider returning edges from computeTargets()?
2. Is the OntologyMaterializer receiving the edges?
3. Is the DataDomain correctly set on the entity?
4. Check for any exceptions in the materialization process.
```

### Property Chain Not Inferring

**Prompt:**
```
Territory → Location edges exist but Associate → Location edges are not being inferred. Debug:

1. Is the property chain defined in the OntologyRegistry?
2. Are Associate → Territory edges being created?
3. Is the Reasoner being invoked after materialization?
4. Check the chain configuration for predicate name mismatches.
```

---

## Summary Checklist

- [ ] Associate has `@OntologyClass` and `@OntologyProperty(predicate = "hasTerritory")` on territory field
- [ ] Territory has `@OntologyClass` annotation
- [ ] Location has `@OntologyClass` annotation
- [ ] `TerritoryLocationEdgeProvider` implemented and annotated with `@ApplicationScoped`
- [ ] Provider injects required repositories (TerritoryRepo, ListResolver)
- [ ] All abstract methods implemented correctly
- [ ] Property chain configured (if using two-phase approach)
- [ ] Integration tests created and passing
- [ ] Provider appears in startup logs as registered
- [ ] Edges appear in database after saving entities

---

## Quick Reference: Method Responsibilities

| Method | Purpose | What to Return |
|--------|---------|----------------|
| `getSourceType()` | CDI discovery | The entity class that triggers this provider |
| `getPredicate()` | Edge labeling | The relationship name (e.g., "hasLocation") |
| `getTargetTypeName()` | Edge metadata | Target entity type name |
| `getAssignedNodeIds()` | Starting points | Hierarchy node IDs from source entity |
| `getHierarchyNode()` | Node loading | Load a single hierarchy node by ID |
| `getChildNodes()` | Hierarchy expansion | All descendants of a node |
| `resolveListItems()` | List resolution | Actual target entities from a node's list |
| `extractTargetId()` | Edge destination | ID of a target entity |
| `getDependencyTypes()` | Change tracking | Entity types that invalidate this provider's edges |
| `getAffectedSourceIds()` | Incremental updates | Source IDs affected by a dependency change |
