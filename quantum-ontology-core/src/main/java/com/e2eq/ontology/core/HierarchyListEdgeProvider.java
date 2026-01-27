package com.e2eq.ontology.core;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Specialized base class for computed edges that follow the pattern:
 * <ol>
 *   <li>Get assigned hierarchy nodes from source entity</li>
 *   <li>Expand to include child nodes (hierarchy traversal)</li>
 *   <li>Resolve each node's list (static or dynamic)</li>
 *   <li>Create edges to resolved items</li>
 * </ol>
 *
 * <p>This is a common pattern for permission/visibility edges, such as:</p>
 * <ul>
 *   <li>Associate → canSeeLocation → Location (via Territory hierarchy)</li>
 *   <li>User → canAccessCustomer → Customer (via Region hierarchy)</li>
 *   <li>Manager → canViewReport → Report (via Department hierarchy)</li>
 * </ul>
 *
 * <b>Example Implementation</b>
 * <pre>{@code
 * @ApplicationScoped
 * public class AssociateCanSeeLocationProvider
 *         extends HierarchyListEdgeProvider<Associate, Territory, Location> {
 *
 *     @Inject TerritoryRepo territoryRepo;
 *     @Inject LocationListResolver listResolver;
 *
 *     @Override protected Class<Associate> getSourceType() { return Associate.class; }
 *     @Override protected String getPredicate() { return "canSeeLocation"; }
 *     @Override protected String getTargetTypeName() { return "Location"; }
 *     @Override protected String getHierarchyTypeName() { return "Territory"; }
 *
 *     @Override
 *     protected List<String> getAssignedNodeIds(Associate source) {
 *         return source.getAssignedTerritories().stream()
 *             .map(ref -> ref.getId().toString())
 *             .toList();
 *     }
 *
 *     @Override
 *     protected Optional<Territory> loadHierarchyNode(ComputationContext ctx, String nodeId) {
 *         return territoryRepo.findById(new ObjectId(nodeId));
 *     }
 *
 *     @Override
 *     protected List<Territory> getChildNodes(ComputationContext ctx, String nodeId) {
 *         return territoryRepo.getAllChildren(new ObjectId(nodeId));
 *     }
 *
 *     @Override
 *     protected List<Location> resolveListItems(ComputationContext ctx, Territory node) {
 *         return listResolver.resolve(ctx.getDataDomainInfo(), node.getStaticDynamicList());
 *     }
 *
 *     @Override
 *     protected String extractTargetId(Location target) {
 *         return target.getId().toString();
 *     }
 * }
 * }</pre>
 *
 * @param <S> source entity type (e.g., Associate)
 * @param <H> hierarchy node type (e.g., Territory) - must have getId(), getRefName(), and list access
 * @param <T> target entity type (e.g., Location)
 */
public abstract class HierarchyListEdgeProvider<S, H, T> extends ComputedEdgeProvider<S> {

    private static final Logger LOG = Logger.getLogger(HierarchyListEdgeProvider.class.getName());

    /**
     * Get the hierarchy node IDs directly assigned to the source entity.
     *
     * @param source the source entity
     * @return list of hierarchy node IDs
     */
    protected abstract List<String> getAssignedNodeIds(S source);

    /**
     * Load a hierarchy node by ID.
     *
     * @param context computation context
     * @param nodeId the node ID
     * @return the hierarchy node, or empty if not found
     */
    protected abstract Optional<H> loadHierarchyNode(ComputationContext context, String nodeId);

    /**
     * Get all child nodes for a given hierarchy node (recursive).
     *
     * <p>This should return all descendants in the hierarchy tree,
     * not just immediate children.</p>
     *
     * @param context computation context
     * @param nodeId the parent node ID
     * @return list of child nodes (may be empty)
     */
    protected abstract List<H> getChildNodes(ComputationContext context, String nodeId);

    /**
     * Resolve the items from a hierarchy node's list.
     *
     * <p>This handles both static lists (direct item references) and
     * dynamic lists (filter-based queries).</p>
     *
     * @param context computation context
     * @param node the hierarchy node
     * @return resolved target items
     */
    protected abstract List<T> resolveListItems(ComputationContext context, H node);

    /**
     * Extract the ID from a target entity.
     *
     * @param target the target entity
     * @return target ID as string
     */
    protected abstract String extractTargetId(T target);

    /**
     * Get the hierarchy node type name for provenance.
     *
     * @return type name (e.g., "Territory")
     */
    protected abstract String getHierarchyTypeName();

    /**
     * Extract the ID from a hierarchy node.
     *
     * <p>Default implementation uses reflection to call getId().
     * Override for custom ID extraction.</p>
     *
     * @param node the hierarchy node
     * @return node ID as string
     */
    protected String extractNodeId(H node) {
        try {
            var method = node.getClass().getMethod("getId");
            Object id = method.invoke(node);
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Cannot extract ID from hierarchy node " + node.getClass() +
                ". Override extractNodeId() for custom ID extraction.", e);
        }
    }

    /**
     * Extract the reference name from a hierarchy node.
     *
     * <p>Default implementation uses reflection to call getRefName().
     * Override for custom reference name extraction.</p>
     *
     * @param node the hierarchy node
     * @return reference name (may be null)
     */
    protected String extractNodeRefName(H node) {
        try {
            var method = node.getClass().getMethod("getRefName");
            Object refName = method.invoke(node);
            return refName != null ? refName.toString() : null;
        } catch (NoSuchMethodException e) {
            // RefName is optional - method doesn't exist, not an error
            LOG.log(Level.FINE, "No getRefName() method on {0}, returning null", node.getClass().getName());
            return null;
        } catch (Exception e) {
            // Unexpected error accessing refName
            LOG.log(Level.WARNING, "Error extracting refName from " + node.getClass().getName(), e);
            return null;
        }
    }

    /**
     * Get list metadata from a hierarchy node for provenance tracking.
     *
     * <p>Default implementation uses reflection to access getStaticDynamicList().
     * Override for custom list access.</p>
     *
     * @param node the hierarchy node
     * @return list metadata, or null if no list
     */
    protected ListMetadata getListMetadata(H node) {
        try {
            var method = node.getClass().getMethod("getStaticDynamicList");
            Object list = method.invoke(node);
            if (list == null) return null;

            // Extract list properties via reflection
            String listId = null;
            String listType = list.getClass().getSimpleName();
            String mode = "UNKNOWN";
            String filterString = null;

            try {
                var getIdMethod = list.getClass().getMethod("getId");
                Object id = getIdMethod.invoke(list);
                listId = id != null ? id.toString() : null;
            } catch (NoSuchMethodException e) {
                LOG.log(Level.FINE, "No getId() method on list {0}", list.getClass().getName());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error extracting list ID from " + list.getClass().getName(), e);
            }

            try {
                var getModeMethod = list.getClass().getMethod("getMode");
                Object modeObj = getModeMethod.invoke(list);
                mode = modeObj != null ? modeObj.toString() : "UNKNOWN";
            } catch (NoSuchMethodException e) {
                LOG.log(Level.FINE, "No getMode() method on list {0}", list.getClass().getName());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error extracting mode from " + list.getClass().getName(), e);
            }

            try {
                var getFilterMethod = list.getClass().getMethod("getFilterString");
                Object filter = getFilterMethod.invoke(list);
                filterString = filter != null ? filter.toString() : null;
            } catch (NoSuchMethodException e) {
                LOG.log(Level.FINE, "No getFilterString() method on list {0}", list.getClass().getName());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error extracting filterString from " + list.getClass().getName(), e);
            }

            return new ListMetadata(listId, listType, mode, filterString);
        } catch (NoSuchMethodException e) {
            // No getStaticDynamicList method - this is expected for nodes without lists
            LOG.log(Level.FINE, "No getStaticDynamicList() method on {0}", node.getClass().getName());
            return null;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error extracting list metadata from " + node.getClass().getName(), e);
            return null;
        }
    }

    /**
     * Metadata about a list for provenance tracking.
     */
    protected record ListMetadata(
        String listId,
        String listType,
        String mode,
        String filterString
    ) {}

    @Override
    protected final Set<ComputedTarget> computeTargets(ComputationContext context, S source) {
        // Map from targetId to accumulated provenance
        Map<String, ProvenanceAccumulator> targetProvenance = new LinkedHashMap<>();

        String sourceType = getSourceType().getSimpleName();
        String sourceId = extractId(source);

        List<String> assignedNodeIds = getAssignedNodeIds(source);

        for (String nodeId : assignedNodeIds) {
            Optional<H> nodeOpt = loadHierarchyNode(context, nodeId);
            if (nodeOpt.isEmpty()) continue;

            H node = nodeOpt.get();

            // Process assigned node (direct assignment)
            processNode(context, node, true, sourceType, sourceId, targetProvenance);

            // Process child nodes (inherited)
            List<H> children = getChildNodes(context, nodeId);
            for (H child : children) {
                processNode(context, child, false, sourceType, sourceId, targetProvenance);
            }
        }

        // Convert to ComputedTarget set
        return targetProvenance.entrySet().stream()
            .map(e -> new ComputedTarget(e.getKey(), e.getValue().build(sourceType, sourceId)))
            .collect(Collectors.toSet());
    }

    private void processNode(ComputationContext context, H node, boolean isDirectAssignment,
                            String sourceType, String sourceId,
                            Map<String, ProvenanceAccumulator> targetProvenance) {

        String nodeId = extractNodeId(node);
        String nodeRefName = extractNodeRefName(node);

        // Create hierarchy contribution for this node
        ComputedEdgeProvenance.HierarchyContribution hierarchyContrib =
            new ComputedEdgeProvenance.HierarchyContribution(
                nodeId, getHierarchyTypeName(), nodeRefName, isDirectAssignment);

        // Resolve list items
        List<T> items = resolveListItems(context, node);

        // Get list metadata for provenance
        ListMetadata listMeta = getListMetadata(node);
        ComputedEdgeProvenance.ListContribution listContrib = null;
        if (listMeta != null) {
            listContrib = new ComputedEdgeProvenance.ListContribution(
                listMeta.listId() != null ? listMeta.listId() : nodeId + "_list",
                listMeta.listType(),
                listMeta.mode(),
                listMeta.filterString(),
                items.size()
            );
        }

        // Create edges with provenance
        for (T item : items) {
            String targetId = extractTargetId(item);

            // Get or create accumulator for this target
            ProvenanceAccumulator accum = targetProvenance.computeIfAbsent(
                targetId, k -> new ProvenanceAccumulator(context.getProviderId()));

            // Add contributions
            accum.addHierarchyContribution(hierarchyContrib);
            if (listContrib != null) {
                accum.addListContribution(listContrib);
            }
        }
    }

    /**
     * Accumulates provenance from multiple paths to the same target.
     */
    private static class ProvenanceAccumulator {
        private final String providerId;
        private final List<ComputedEdgeProvenance.HierarchyContribution> hierarchyPath = new ArrayList<>();
        private final List<ComputedEdgeProvenance.ListContribution> resolvedLists = new ArrayList<>();

        ProvenanceAccumulator(String providerId) {
            this.providerId = providerId;
        }

        void addHierarchyContribution(ComputedEdgeProvenance.HierarchyContribution contrib) {
            // Avoid duplicates
            if (!hierarchyPath.contains(contrib)) {
                hierarchyPath.add(contrib);
            }
        }

        void addListContribution(ComputedEdgeProvenance.ListContribution contrib) {
            // Avoid duplicates (by listId)
            boolean exists = resolvedLists.stream()
                .anyMatch(l -> Objects.equals(l.listId(), contrib.listId()));
            if (!exists) {
                resolvedLists.add(contrib);
            }
        }

        ComputedEdgeProvenance build(String sourceType, String sourceId) {
            return new ComputedEdgeProvenance(
                providerId,
                sourceType,
                sourceId,
                new ArrayList<>(hierarchyPath),
                new ArrayList<>(resolvedLists),
                new Date()
            );
        }
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        // Subclasses should override to include their hierarchy and list types
        // Example: return Set.of(Territory.class, LocationList.class);
        return Set.of();
    }
}
