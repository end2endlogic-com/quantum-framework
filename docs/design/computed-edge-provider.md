# Computed Edge Provider Design Document

## Overview

This document describes the design for **Computed Edge Providers** - a mechanism for applications to implement complex, multi-step logic for deriving ontology edges that go beyond what simple property chains can express.

### Problem Statement

The current property chain mechanism works well for declarative multi-hop traversals:
```yaml
chains:
  - chain: [placedBy, memberOf]
    implies: placedInOrg
```

However, some edge computations require complex logic that property chains cannot express:

**Example: Associate → canSeeLocation → Location**
1. Get assigned territories for the Associate
2. Expand to include all child territories (hierarchy traversal)
3. For each territory, resolve its LocationList (static or dynamic filters)
4. Create edges from Associate to each resolved Location

This involves:
- Hierarchy traversal (parent-child expansion)
- Conditional logic (static vs. dynamic list resolution)
- Cross-entity queries
- Aggregation from multiple sources

### Goals

1. **Extensibility**: Applications can implement custom edge computation logic
2. **Provenance**: Track which hierarchy nodes and lists contributed to computed edges
3. **Incremental Updates**: When dependencies change (e.g., Territory's LocationList), recompute affected edges
4. **Reusability**: Provide base classes for common patterns (hierarchy + list resolution)
5. **Multi-tenant Safety**: All edges scoped by DataDomainInfo

---

## Architecture

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Application Layer                            │
├─────────────────────────────────────────────────────────────────────┤
│  AssociateCanSeeLocationProvider extends HierarchyListEdgeProvider  │
│  CustomerCanSeeOrderProvider extends ComputedEdgeProvider           │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      quantum-ontology-core                           │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────┐  ┌─────────────────────────────────┐ │
│  │ ComputedEdgeProvider     │  │ HierarchyListEdgeProvider       │ │
│  │ (abstract base)          │  │ (specialized for hierarchy+list)│ │
│  └──────────────────────────┘  └─────────────────────────────────┘ │
│                                                                      │
│  ┌──────────────────────────┐  ┌─────────────────────────────────┐ │
│  │ ComputedEdgeProvenance   │  │ ComputedEdgeRegistry            │ │
│  │ (tracks contributions)   │  │ (tracks dependencies for        │ │
│  │                          │  │  incremental recomputation)     │ │
│  └──────────────────────────┘  └─────────────────────────────────┘ │
│                                                                      │
│  ┌──────────────────────────┐                                       │
│  │ OntologyEdgeProvider     │  ← existing SPI interface             │
│  │ (interface)              │                                       │
│  └──────────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      quantum-ontology-mongo                          │
├─────────────────────────────────────────────────────────────────────┤
│  OntologyMaterializer - invokes providers during entity persistence │
│  MongoEdgeStore - persists computed edges with provenance           │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. ComputedEdgeProvenance

Tracks which entities/nodes contributed to a computed edge.

```java
package com.e2eq.ontology.core;

/**
 * Provenance information for a computed edge, tracking which entities
 * and data sources contributed to its computation.
 */
public record ComputedEdgeProvenance(
    /** Unique identifier for the provider that created this edge */
    String providerId,

    /** The source entity type and ID that triggered computation */
    String sourceEntityType,
    String sourceEntityId,

    /** Hierarchy nodes traversed (if applicable) */
    List<HierarchyContribution> hierarchyPath,

    /** Lists resolved during computation */
    List<ListContribution> resolvedLists,

    /** Timestamp of computation */
    Date computedAt
) {

    /**
     * A hierarchy node that contributed to the edge.
     */
    public record HierarchyContribution(
        String nodeId,
        String nodeType,
        String nodeRefName,
        boolean isDirectAssignment  // true if directly assigned, false if inherited
    ) {}

    /**
     * A list (static or dynamic) that contributed to the edge.
     */
    public record ListContribution(
        String listId,
        String listType,
        StaticDynamicList.Mode mode,  // STATIC or DYNAMIC
        String filterString,           // if dynamic
        int itemCount                  // number of items resolved
    ) {}
}
```

### 2. ComputedEdgeProvider (Abstract Base Class)

```java
package com.e2eq.ontology.core;

/**
 * Base class for computing ontology edges that require complex multi-step logic
 * beyond what property chains can express.
 *
 * <p>Typical use cases:</p>
 * <ul>
 *   <li>Hierarchy expansion + list resolution (e.g., Associate → canSeeLocation → Location)</li>
 *   <li>Multi-hop traversal with conditional logic</li>
 *   <li>Aggregated permissions from multiple sources</li>
 * </ul>
 *
 * <p>Implementations are registered as CDI beans and discovered automatically.</p>
 *
 * @param <S> the source entity type
 */
public abstract class ComputedEdgeProvider<S> implements OntologyEdgeProvider {

    /**
     * Unique identifier for this provider, used in provenance tracking.
     * Default implementation returns the simple class name.
     */
    public String getProviderId() {
        return getClass().getSimpleName();
    }

    /**
     * The source entity type this provider handles.
     */
    protected abstract Class<S> getSourceType();

    /**
     * The predicate for edges created by this provider.
     */
    protected abstract String getPredicate();

    /**
     * The target entity type name (for edge metadata).
     */
    protected abstract String getTargetTypeName();

    /**
     * Core logic: compute target entity IDs from a source entity.
     *
     * <p>Implementations should populate the context with provenance information
     * about which hierarchy nodes and lists contributed to the computation.</p>
     *
     * @param context computation context with realm, domain, and provenance tracking
     * @param source the source entity
     * @return set of computed targets with individual provenance
     */
    protected abstract Set<ComputedTarget> computeTargets(ComputationContext context, S source);

    /**
     * Returns the entity types that, when modified, should trigger recomputation
     * of edges from this provider.
     *
     * <p>For example, if Associate→canSeeLocation depends on Territory and LocationList,
     * this method should return [Territory.class, LocationList.class].</p>
     */
    public Set<Class<?>> getDependencyTypes() {
        return Set.of();
    }

    /**
     * Given a changed dependency entity, return the source entity IDs that need
     * their computed edges recomputed.
     *
     * <p>For example, if a Territory's LocationList changes, return the IDs of
     * all Associates assigned to that Territory (directly or via hierarchy).</p>
     *
     * @param context computation context
     * @param dependencyType the type of entity that changed
     * @param dependencyId the ID of the entity that changed
     * @return set of source entity IDs needing recomputation
     */
    public Set<String> getAffectedSourceIds(
            ComputationContext context,
            Class<?> dependencyType,
            String dependencyId) {
        return Set.of();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OntologyEdgeProvider implementation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public final boolean supports(Class<?> entityType) {
        return getSourceType().isAssignableFrom(entityType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public final List<Reasoner.Edge> edges(String realmId, DataDomainInfo domain, Object entity) {
        S source = (S) entity;
        String sourceId = extractId(source);
        String sourceTypeName = getSourceType().getSimpleName();

        ComputationContext context = new ComputationContext(realmId, domain, getProviderId());
        Set<ComputedTarget> targets = computeTargets(context, source);

        return targets.stream()
            .map(target -> createEdge(sourceId, sourceTypeName, target))
            .toList();
    }

    private Reasoner.Edge createEdge(String sourceId, String sourceType, ComputedTarget target) {
        Map<String, Object> provMap = new LinkedHashMap<>();
        provMap.put("providerId", getProviderId());
        provMap.put("computedAt", new Date());

        if (target.provenance() != null) {
            if (!target.provenance().hierarchyPath().isEmpty()) {
                provMap.put("hierarchyPath", target.provenance().hierarchyPath().stream()
                    .map(h -> Map.of(
                        "nodeId", h.nodeId(),
                        "nodeType", h.nodeType(),
                        "nodeRefName", h.nodeRefName(),
                        "isDirectAssignment", h.isDirectAssignment()
                    ))
                    .toList());
            }
            if (!target.provenance().resolvedLists().isEmpty()) {
                provMap.put("resolvedLists", target.provenance().resolvedLists().stream()
                    .map(l -> Map.of(
                        "listId", l.listId(),
                        "mode", l.mode().name(),
                        "filterString", l.filterString() != null ? l.filterString() : "",
                        "itemCount", l.itemCount()
                    ))
                    .toList());
            }
        }

        return new Reasoner.Edge(
            sourceId, sourceType,
            getPredicate(),
            target.targetId(), getTargetTypeName(),
            false,  // not inferred by reasoner - explicitly computed
            Optional.of(new Reasoner.Provenance("computed", provMap))
        );
    }

    protected String extractId(S entity) {
        if (entity instanceof BaseModel bm) {
            return bm.getId().toString();
        }
        throw new IllegalArgumentException("Cannot extract ID from " + entity.getClass() +
            ". Override extractId() for custom ID extraction.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Supporting types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Context for edge computation, providing access to realm, domain,
     * and provenance tracking.
     */
    public static class ComputationContext {
        private final String realmId;
        private final DataDomainInfo dataDomainInfo;
        private final String providerId;
        private final List<ComputedEdgeProvenance.HierarchyContribution> hierarchyPath = new ArrayList<>();
        private final List<ComputedEdgeProvenance.ListContribution> resolvedLists = new ArrayList<>();

        public ComputationContext(String realmId, DataDomainInfo dataDomainInfo, String providerId) {
            this.realmId = realmId;
            this.dataDomainInfo = dataDomainInfo;
            this.providerId = providerId;
        }

        public String getRealmId() { return realmId; }
        public DataDomainInfo getDataDomainInfo() { return dataDomainInfo; }
        public String getProviderId() { return providerId; }

        /** Record a hierarchy node contribution */
        public void addHierarchyContribution(String nodeId, String nodeType,
                                              String nodeRefName, boolean isDirectAssignment) {
            hierarchyPath.add(new ComputedEdgeProvenance.HierarchyContribution(
                nodeId, nodeType, nodeRefName, isDirectAssignment));
        }

        /** Record a list resolution contribution */
        public void addListContribution(String listId, String listType,
                                        StaticDynamicList.Mode mode, String filterString, int itemCount) {
            resolvedLists.add(new ComputedEdgeProvenance.ListContribution(
                listId, listType, mode, filterString, itemCount));
        }

        /** Build provenance for a computed target */
        public ComputedEdgeProvenance buildProvenance(String sourceType, String sourceId) {
            return new ComputedEdgeProvenance(
                providerId, sourceType, sourceId,
                new ArrayList<>(hierarchyPath),
                new ArrayList<>(resolvedLists),
                new Date()
            );
        }

        /** Clear accumulated provenance (for computing multiple targets) */
        public void clearProvenance() {
            hierarchyPath.clear();
            resolvedLists.clear();
        }
    }

    /**
     * A computed target with optional individual provenance.
     */
    public record ComputedTarget(
        String targetId,
        ComputedEdgeProvenance provenance
    ) {
        public ComputedTarget(String targetId) {
            this(targetId, null);
        }
    }
}
```

### 3. HierarchyListEdgeProvider (Specialized Base Class)

For the common pattern of hierarchy expansion + list resolution:

```java
package com.e2eq.ontology.core;

/**
 * Specialized base class for computed edges that follow the pattern:
 * <ol>
 *   <li>Get assigned hierarchy nodes from source entity</li>
 *   <li>Expand to include child nodes (hierarchy traversal)</li>
 *   <li>Resolve each node's StaticDynamicList</li>
 *   <li>Create edges to resolved items</li>
 * </ol>
 *
 * <p>Example: Associate → canSeeLocation → Location</p>
 *
 * @param <S> source entity type (e.g., Associate)
 * @param <H> hierarchy node type (e.g., Territory)
 * @param <T> target entity type (e.g., Location)
 */
public abstract class HierarchyListEdgeProvider<S, H extends HierarchicalModel<H, T, ?>, T extends UnversionedBaseModel>
        extends ComputedEdgeProvider<S> {

    /**
     * Get the hierarchy node IDs directly assigned to the source entity.
     */
    protected abstract List<String> getAssignedNodeIds(S source);

    /**
     * Load a hierarchy node by ID.
     */
    protected abstract Optional<H> loadHierarchyNode(ComputationContext context, String nodeId);

    /**
     * Get all child nodes for a given hierarchy node (recursive).
     */
    protected abstract List<H> getChildNodes(ComputationContext context, String nodeId);

    /**
     * Resolve the items from a hierarchy node's StaticDynamicList.
     *
     * @param context computation context
     * @param node the hierarchy node
     * @return resolved items
     */
    protected abstract List<T> resolveListItems(ComputationContext context, H node);

    /**
     * Extract the ID from a target entity.
     */
    protected abstract String extractTargetId(T target);

    /**
     * Get the hierarchy node type name for provenance.
     */
    protected abstract String getHierarchyTypeName();

    @Override
    protected final Set<ComputedTarget> computeTargets(ComputationContext context, S source) {
        Map<String, ComputedEdgeProvenance> targetProvenance = new LinkedHashMap<>();

        List<String> assignedNodeIds = getAssignedNodeIds(source);

        for (String nodeId : assignedNodeIds) {
            Optional<H> nodeOpt = loadHierarchyNode(context, nodeId);
            if (nodeOpt.isEmpty()) continue;

            H node = nodeOpt.get();

            // Process assigned node (direct assignment)
            processNode(context, node, true, targetProvenance);

            // Process child nodes (inherited)
            List<H> children = getChildNodes(context, nodeId);
            for (H child : children) {
                processNode(context, child, false, targetProvenance);
            }
        }

        return targetProvenance.entrySet().stream()
            .map(e -> new ComputedTarget(e.getKey(), e.getValue()))
            .collect(Collectors.toSet());
    }

    private void processNode(ComputationContext context, H node, boolean isDirectAssignment,
                            Map<String, ComputedEdgeProvenance> targetProvenance) {
        String nodeId = node.getId().toString();
        String nodeRefName = node.getRefName();

        // Resolve list items
        List<T> items = resolveListItems(context, node);

        // Record list contribution
        StaticDynamicList<?> list = node.getStaticDynamicList();
        if (list != null) {
            context.addListContribution(
                list.getId() != null ? list.getId().toString() : nodeId + "_list",
                list.getClass().getSimpleName(),
                list.getMode(),
                list.getFilterString(),
                items.size()
            );
        }

        // Create edges with provenance
        for (T item : items) {
            String targetId = extractTargetId(item);

            // Build provenance for this specific path
            context.addHierarchyContribution(nodeId, getHierarchyTypeName(),
                                             nodeRefName, isDirectAssignment);

            // Merge provenance if target already reached via another path
            ComputedEdgeProvenance existing = targetProvenance.get(targetId);
            if (existing != null) {
                // Merge hierarchy paths
                List<ComputedEdgeProvenance.HierarchyContribution> mergedPath =
                    new ArrayList<>(existing.hierarchyPath());
                mergedPath.addAll(context.buildProvenance(
                    getSourceType().getSimpleName(),
                    extractId((S) null) // placeholder - we merge anyway
                ).hierarchyPath());

                targetProvenance.put(targetId, new ComputedEdgeProvenance(
                    existing.providerId(),
                    existing.sourceEntityType(),
                    existing.sourceEntityId(),
                    mergedPath,
                    existing.resolvedLists(),
                    existing.computedAt()
                ));
            } else {
                targetProvenance.put(targetId, context.buildProvenance(
                    getSourceType().getSimpleName(),
                    "" // will be filled by caller
                ));
            }

            context.clearProvenance();
        }
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        // Subclasses should override to include their hierarchy and list types
        return Set.of();
    }
}
```

### 4. ComputedEdgeRegistry

Tracks provider dependencies for incremental recomputation:

```java
package com.e2eq.ontology.core;

/**
 * Registry that tracks computed edge provider dependencies for incremental updates.
 *
 * When a dependency entity changes, this registry can identify which providers
 * need to recompute edges and which source entities are affected.
 */
@ApplicationScoped
public class ComputedEdgeRegistry {

    private final Map<Class<?>, List<ComputedEdgeProvider<?>>> dependencyToProviders = new ConcurrentHashMap<>();
    private final List<ComputedEdgeProvider<?>> allProviders = new CopyOnWriteArrayList<>();

    /**
     * Register a provider with its dependencies.
     */
    public void register(ComputedEdgeProvider<?> provider) {
        allProviders.add(provider);
        for (Class<?> depType : provider.getDependencyTypes()) {
            dependencyToProviders.computeIfAbsent(depType, k -> new CopyOnWriteArrayList<>())
                .add(provider);
        }
    }

    /**
     * Get providers that depend on the given entity type.
     */
    public List<ComputedEdgeProvider<?>> getProvidersForDependency(Class<?> entityType) {
        List<ComputedEdgeProvider<?>> result = new ArrayList<>();

        // Check exact type and supertypes
        for (Map.Entry<Class<?>, List<ComputedEdgeProvider<?>>> entry : dependencyToProviders.entrySet()) {
            if (entry.getKey().isAssignableFrom(entityType)) {
                result.addAll(entry.getValue());
            }
        }

        return result;
    }

    /**
     * Get all registered providers.
     */
    public List<ComputedEdgeProvider<?>> getAllProviders() {
        return Collections.unmodifiableList(allProviders);
    }

    /**
     * Find affected source entities when a dependency changes.
     *
     * @param realmId realm identifier
     * @param domain data domain
     * @param dependencyType type of changed entity
     * @param dependencyId ID of changed entity
     * @return map of provider to affected source entity IDs
     */
    public Map<ComputedEdgeProvider<?>, Set<String>> findAffectedSources(
            String realmId,
            DataDomainInfo domain,
            Class<?> dependencyType,
            String dependencyId) {

        Map<ComputedEdgeProvider<?>, Set<String>> result = new LinkedHashMap<>();

        for (ComputedEdgeProvider<?> provider : getProvidersForDependency(dependencyType)) {
            ComputedEdgeProvider.ComputationContext ctx =
                new ComputedEdgeProvider.ComputationContext(realmId, domain, provider.getProviderId());

            Set<String> affected = provider.getAffectedSourceIds(ctx, dependencyType, dependencyId);
            if (!affected.isEmpty()) {
                result.put(provider, affected);
            }
        }

        return result;
    }
}
```

### 5. Integration with OntologyMaterializer

The existing `OntologyMaterializer` needs enhancement to trigger recomputation:

```java
// Addition to OntologyMaterializer or new class: ComputedEdgeRecomputeHandler

@ApplicationScoped
public class ComputedEdgeRecomputeHandler {

    @Inject
    ComputedEdgeRegistry registry;

    @Inject
    EdgeStore edgeStore;

    /**
     * Called when an entity is modified that might affect computed edges.
     *
     * @param realmId realm identifier
     * @param domain data domain
     * @param entityType type of modified entity
     * @param entityId ID of modified entity
     */
    public void onEntityModified(String realmId, DataDomainInfo domain,
                                  Class<?> entityType, String entityId) {

        Map<ComputedEdgeProvider<?>, Set<String>> affected =
            registry.findAffectedSources(realmId, domain, entityType, entityId);

        for (Map.Entry<ComputedEdgeProvider<?>, Set<String>> entry : affected.entrySet()) {
            ComputedEdgeProvider<?> provider = entry.getKey();
            Set<String> sourceIds = entry.getValue();

            for (String sourceId : sourceIds) {
                recomputeEdgesForSource(realmId, domain, provider, sourceId);
            }
        }
    }

    private void recomputeEdgesForSource(String realmId, DataDomainInfo domain,
                                          ComputedEdgeProvider<?> provider, String sourceId) {
        // 1. Load source entity
        // 2. Call provider.edges()
        // 3. Delete old computed edges for this source+predicate
        // 4. Insert new computed edges
        // Implementation details depend on entity loading mechanism
    }
}
```

---

## Example Application Implementation

```java
@ApplicationScoped
public class AssociateCanSeeLocationProvider
        extends HierarchyListEdgeProvider<Associate, Territory, Location> {

    @Inject
    TerritoryRepo territoryRepo;

    @Inject
    LocationListResolver locationListResolver;

    @Inject
    AssociateRepo associateRepo;

    @Override
    protected Class<Associate> getSourceType() { return Associate.class; }

    @Override
    protected String getPredicate() { return "canSeeLocation"; }

    @Override
    protected String getTargetTypeName() { return "Location"; }

    @Override
    protected String getHierarchyTypeName() { return "Territory"; }

    @Override
    protected List<String> getAssignedNodeIds(Associate source) {
        return source.getAssignedTerritories().stream()
            .map(ref -> ref.getId().toString())
            .toList();
    }

    @Override
    protected Optional<Territory> loadHierarchyNode(ComputationContext context, String nodeId) {
        return territoryRepo.findById(new ObjectId(nodeId));
    }

    @Override
    protected List<Territory> getChildNodes(ComputationContext context, String nodeId) {
        return territoryRepo.getAllChildren(new ObjectId(nodeId));
    }

    @Override
    protected List<Location> resolveListItems(ComputationContext context, Territory node) {
        return locationListResolver.resolve(
            context.getDataDomainInfo(),
            node.getStaticDynamicList()
        );
    }

    @Override
    protected String extractTargetId(Location target) {
        return target.getId().toString();
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        return Set.of(Territory.class, LocationList.class);
    }

    @Override
    public Set<String> getAffectedSourceIds(ComputationContext context,
                                             Class<?> dependencyType, String dependencyId) {
        if (Territory.class.isAssignableFrom(dependencyType)) {
            // Find associates assigned to this territory or its ancestors
            return associateRepo.findByAssignedTerritory(dependencyId).stream()
                .map(a -> a.getId().toString())
                .collect(Collectors.toSet());
        }
        // Similar logic for LocationList changes
        return Set.of();
    }
}
```

---

## Provenance Storage

Computed edges are stored with rich provenance in the `prov` field:

```json
{
  "src": "associate-123",
  "srcType": "Associate",
  "p": "canSeeLocation",
  "dst": "location-456",
  "dstType": "Location",
  "inferred": false,
  "prov": {
    "rule": "computed",
    "providerId": "AssociateCanSeeLocationProvider",
    "computedAt": "2025-01-21T10:30:00Z",
    "hierarchyPath": [
      {
        "nodeId": "territory-west",
        "nodeType": "Territory",
        "nodeRefName": "west-region",
        "isDirectAssignment": true
      },
      {
        "nodeId": "territory-west-sub1",
        "nodeType": "Territory",
        "nodeRefName": "west-sub-region-1",
        "isDirectAssignment": false
      }
    ],
    "resolvedLists": [
      {
        "listId": "loclist-west-sub1",
        "mode": "DYNAMIC",
        "filterString": "state:^[CA,OR,WA]",
        "itemCount": 15
      }
    ]
  }
}
```

---

## Incremental Update Flow

```
Territory "west-sub1" LocationList modified
                │
                ▼
    OntologyWriteHook detects change
                │
                ▼
    ComputedEdgeRecomputeHandler.onEntityModified(
        realmId, domain, Territory.class, "west-sub1")
                │
                ▼
    ComputedEdgeRegistry.findAffectedSources()
                │
                ▼
    AssociateCanSeeLocationProvider.getAffectedSourceIds()
    returns: ["associate-123", "associate-456"]
                │
                ▼
    For each affected associate:
      1. Load Associate entity
      2. Call provider.edges() to recompute
      3. Delete old edges: (associate-123, canSeeLocation, *)
      4. Insert new computed edges with fresh provenance
```

---

## File Locations

| Component | Location |
|-----------|----------|
| `ComputedEdgeProvider` | `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/ComputedEdgeProvider.java` |
| `ComputedEdgeProvenance` | `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/ComputedEdgeProvenance.java` |
| `HierarchyListEdgeProvider` | `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/HierarchyListEdgeProvider.java` |
| `ComputedEdgeRegistry` | `quantum-ontology-core/src/main/java/com/e2eq/ontology/core/ComputedEdgeRegistry.java` |
| `ComputedEdgeRecomputeHandler` | `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/mongo/ComputedEdgeRecomputeHandler.java` |
| Tests | `quantum-ontology-mongo/src/test/java/com/e2eq/ontology/mongo/it/ComputedEdgeProviderIT.java` |

---

## Open Questions / Decisions

1. **Batch Recomputation**: Should we support batch recomputation for bulk imports?
2. **Async Processing**: Should incremental recomputation be async (event-driven) or sync?
3. **Edge Versioning**: Should we track edge version/generation for optimistic concurrency?
4. **Caching**: Future enhancement - LRU cache for expensive computations?

---

## Implementation Phases

### Phase 1: Core Framework (This PR)
- [ ] `ComputedEdgeProvenance` record
- [ ] `ComputedEdgeProvider` abstract base class
- [ ] `HierarchyListEdgeProvider` specialized base class
- [ ] Unit tests

### Phase 2: Registry & Recomputation
- [ ] `ComputedEdgeRegistry` for dependency tracking
- [ ] `ComputedEdgeRecomputeHandler` for incremental updates
- [ ] Integration with `OntologyWriteHook`

### Phase 3: Integration Test
- [ ] Example `AssociateCanSeeLocationProvider`
- [ ] End-to-end integration test

---

## References

- Existing `OntologyEdgeProvider` SPI: `quantum-ontology-core/.../spi/OntologyEdgeProvider.java`
- `HierarchicalModel` and `HierarchicalRepo`: `quantum-models/.../HierarchicalModel.java`
- `StaticDynamicList`: `quantum-models/.../StaticDynamicList.java`
- `EdgeRecord` with provenance: `quantum-ontology-core/.../EdgeRecord.java`
