package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.spi.OntologyEdgeProvider;

import java.util.*;

/**
 * Base class for computing ontology edges that require complex multi-step logic
 * beyond what property chains can express.
 *
 * <p>Typical use cases:</p>
 * <ul>
 *   <li>Hierarchy expansion + list resolution (e.g., Associate → canSeeLocation → Location)</li>
 *   <li>Multi-hop traversal with conditional logic</li>
 *   <li>Aggregated permissions from multiple sources</li>
 *   <li>Dynamic edge computation based on business rules</li>
 * </ul>
 *
 * <p>Unlike simple property chains defined in YAML, ComputedEdgeProvider allows
 * full programmatic control over edge computation, including:</p>
 * <ul>
 *   <li>Database queries to load related entities</li>
 *   <li>Hierarchy traversal with cycle detection</li>
 *   <li>Static vs. dynamic list resolution</li>
 *   <li>Complex conditional logic</li>
 * </ul>
 *
 * <p>Implementations are registered as CDI beans (e.g., @ApplicationScoped) and
 * discovered automatically by the framework.</p>
 *
 * <b>Example Implementation</b>
 * <pre>{@code
 * @ApplicationScoped
 * public class AssociateCanSeeLocationProvider extends ComputedEdgeProvider<Associate> {
 *
 *     @Inject TerritoryRepo territoryRepo;
 *
 *     @Override protected Class<Associate> getSourceType() { return Associate.class; }
 *     @Override protected String getPredicate() { return "canSeeLocation"; }
 *     @Override protected String getTargetTypeName() { return "Location"; }
 *
 *     @Override
 *     protected Set<ComputedTarget> computeTargets(ComputationContext ctx, Associate assoc) {
 *         Set<ComputedTarget> targets = new HashSet<>();
 *         // ... compute locations from territories ...
 *         return targets;
 *     }
 * }
 * }</pre>
 *
 * @param <S> the source entity type
 */
public abstract class ComputedEdgeProvider<S> implements OntologyEdgeProvider {

    /**
     * Unique identifier for this provider, used in provenance tracking.
     * Default implementation returns the simple class name.
     *
     * @return provider identifier
     */
    public String getProviderId() {
        return getClass().getSimpleName();
    }

    /**
     * The source entity type this provider handles.
     *
     * @return class of the source entity
     */
    public abstract Class<S> getSourceType();

    /**
     * The predicate/property name for edges created by this provider.
     *
     * @return predicate string (e.g., "canSeeLocation")
     */
    public abstract String getPredicate();

    /**
     * The target entity type name for edge metadata.
     *
     * @return target type name (e.g., "Location")
     */
    public abstract String getTargetTypeName();

    /**
     * The source entity type name for edge metadata.
     *
     * <p>Default implementation resolves the ontology class ID from the
     * {@link OntologyClass} annotation on the source type, falling back to
     * the simple class name if no annotation is present.</p>
     *
     * <p>Override this method if you need custom source type naming.</p>
     *
     * @return source type name (e.g., "Credential" for CredentialUserIdPassword)
     */
    public String getSourceTypeName() {
        return resolveOntologyClassId(getSourceType());
    }

    /**
     * Resolves the ontology class ID for a given class.
     *
     * <p>Checks for {@link OntologyClass} annotation and returns its id if present
     * and non-empty, otherwise returns the simple class name.</p>
     *
     * @param clazz the class to resolve
     * @return ontology class ID or simple class name
     */
    protected static String resolveOntologyClassId(Class<?> clazz) {
        OntologyClass annotation = clazz.getAnnotation(OntologyClass.class);
        if (annotation != null && !annotation.id().isEmpty()) {
            return annotation.id();
        }
        return clazz.getSimpleName();
    }

    /**
     * Core logic: compute target entity IDs from a source entity.
     *
     * <p>Implementations should use the context to track provenance information
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
     * this method should return {@code Set.of(Territory.class, LocationList.class)}.</p>
     *
     * <p>Override this method to enable incremental updates when dependencies change.</p>
     *
     * @return set of dependency entity types (empty by default)
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
     * <p>Override this method to enable incremental updates when dependencies change.</p>
     *
     * @param context computation context
     * @param dependencyType the type of entity that changed
     * @param dependencyId the ID of the entity that changed
     * @return set of source entity IDs needing recomputation (empty by default)
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
        String sourceTypeName = getSourceTypeName();

        ComputationContext context = new ComputationContext(realmId, domain, getProviderId());
        Set<ComputedTarget> targets = computeTargets(context, source);

        return targets.stream()
            .map(target -> createEdge(sourceId, sourceTypeName, target))
            .toList();
    }

    /**
     * Creates a Reasoner.Edge from a computed target with provenance.
     */
    private Reasoner.Edge createEdge(String sourceId, String sourceType, ComputedTarget target) {
        Map<String, Object> provMap = new LinkedHashMap<>();
        provMap.put("providerId", getProviderId());
        provMap.put("computedAt", new Date());

        ComputedEdgeProvenance prov = target.provenance();
        if (prov != null) {
            if (prov.hierarchyPath() != null && !prov.hierarchyPath().isEmpty()) {
                provMap.put("hierarchyPath", prov.hierarchyPath().stream()
                    .map(h -> {
                        Map<String, Object> hMap = new LinkedHashMap<>();
                        hMap.put("nodeId", h.nodeId());
                        hMap.put("nodeType", h.nodeType());
                        hMap.put("nodeRefName", h.nodeRefName());
                        hMap.put("isDirectAssignment", h.isDirectAssignment());
                        return hMap;
                    })
                    .toList());
            }
            if (prov.resolvedLists() != null && !prov.resolvedLists().isEmpty()) {
                provMap.put("resolvedLists", prov.resolvedLists().stream()
                    .map(l -> {
                        Map<String, Object> lMap = new LinkedHashMap<>();
                        lMap.put("listId", l.listId());
                        lMap.put("listType", l.listType());
                        lMap.put("mode", l.mode());
                        if (l.filterString() != null) {
                            lMap.put("filterString", l.filterString());
                        }
                        lMap.put("itemCount", l.itemCount());
                        return lMap;
                    })
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

    /**
     * Extract the ID from a source entity.
     *
     * <p>Default implementation handles entities with getId() method returning
     * ObjectId or String. Override for custom ID extraction.</p>
     *
     * @param entity the source entity
     * @return entity ID as string
     */
    protected String extractId(S entity) {
        // Use reflection to find getId() method
        try {
            var method = entity.getClass().getMethod("getId");
            Object id = method.invoke(entity);
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Cannot extract ID from " + entity.getClass() +
                ". Override extractId() for custom ID extraction.", e);
        }
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

        /**
         * Record a hierarchy node contribution to provenance.
         *
         * @param nodeId unique ID of the hierarchy node
         * @param nodeType type name (e.g., "Territory")
         * @param nodeRefName human-readable reference name
         * @param isDirectAssignment true if directly assigned, false if inherited
         */
        public void addHierarchyContribution(String nodeId, String nodeType,
                                              String nodeRefName, boolean isDirectAssignment) {
            hierarchyPath.add(new ComputedEdgeProvenance.HierarchyContribution(
                nodeId, nodeType, nodeRefName, isDirectAssignment));
        }

        /**
         * Record a list resolution contribution to provenance.
         *
         * @param listId unique ID of the list
         * @param listType type name (e.g., "LocationList")
         * @param mode STATIC or DYNAMIC
         * @param filterString filter string for dynamic lists (null for static)
         * @param itemCount number of items resolved
         */
        public void addListContribution(String listId, String listType,
                                        String mode, String filterString, int itemCount) {
            resolvedLists.add(new ComputedEdgeProvenance.ListContribution(
                listId, listType, mode, filterString, itemCount));
        }

        /**
         * Build provenance for a computed target using accumulated contributions.
         *
         * @param sourceType source entity type name
         * @param sourceId source entity ID
         * @return provenance record
         */
        public ComputedEdgeProvenance buildProvenance(String sourceType, String sourceId) {
            return new ComputedEdgeProvenance(
                providerId, sourceType, sourceId,
                new ArrayList<>(hierarchyPath),
                new ArrayList<>(resolvedLists),
                new Date()
            );
        }

        /**
         * Clear accumulated provenance (for computing multiple targets).
         */
        public void clearProvenance() {
            hierarchyPath.clear();
            resolvedLists.clear();
        }

        /**
         * Get current hierarchy path (for inspection).
         */
        public List<ComputedEdgeProvenance.HierarchyContribution> getHierarchyPath() {
            return Collections.unmodifiableList(hierarchyPath);
        }

        /**
         * Get current resolved lists (for inspection).
         */
        public List<ComputedEdgeProvenance.ListContribution> getResolvedLists() {
            return Collections.unmodifiableList(resolvedLists);
        }
    }

    /**
     * A computed target with optional individual provenance.
     *
     * <p>Each target represents an edge destination, along with the provenance
     * information explaining how it was reached.</p>
     */
    public record ComputedTarget(
        /** The target entity ID */
        String targetId,

        /** Provenance explaining how this target was computed (may be null) */
        ComputedEdgeProvenance provenance
    ) {
        /**
         * Creates a target without provenance.
         */
        public ComputedTarget(String targetId) {
            this(targetId, null);
        }
    }
}
