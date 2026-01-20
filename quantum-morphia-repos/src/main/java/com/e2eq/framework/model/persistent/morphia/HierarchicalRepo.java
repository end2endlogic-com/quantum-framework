package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.fasterxml.jackson.core.TreeNode;
import com.mongodb.client.MongoCursor;
import dev.morphia.Datastore;
import dev.morphia.aggregation.Aggregation;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.NotFoundException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

import static dev.morphia.aggregation.stages.GraphLookup.graphLookup;
import static dev.morphia.query.filters.Filters.eq;

// T - The hierarchical Model
// O - The object that a static or dynamic list represents at each level in hiearchy
// L - The staticDynamicList of object O's
// R - The repository for static or dynamic list of object O's'

/**
 *  Creates a hierarchical repository for hierarchical models.
 * @param <T> - the HierarchicalModel type
 * @param <O> - The object that a static or dynamic list represents
 * @param <L> - The static dynamic list of object O's
 * @param <OR> - The repository of O's
 * @param <LR> - The repository for the static or dynamic list of Object O's
 */
public abstract class HierarchicalRepo<
        T extends HierarchicalModel<T,O,L>,
        O extends UnversionedBaseModel,
        L extends StaticDynamicList<O>,
        OR extends MorphiaRepo<O>,
        LR extends ObjectListRepo< O, L, OR>> extends MorphiaRepo<T>{

    @Inject
    LR objectListRepo;

    @Inject
    OR objectRepo;

    @Override
    public long delete(@NotNull ObjectId id) throws ReferentialIntegrityViolationException {
        Optional<T> optionalExisting = findById(id);
        if (optionalExisting.isPresent()) {
            T existing = optionalExisting.get();
            if (existing.getParent() != null) {
                Optional<T> oParent = findById(existing.getParent().getEntityId());
                if (oParent.isPresent()) {
                    oParent.get().getDescendants().remove(existing.getId());
                    super.save(oParent.get());
                }
            }
            return super.delete(optionalExisting.get());
        } else {
            return 0;
        }
    }


    @Override
    public long delete(@NotNull("the datastore must not be null") Datastore datastore, T node) throws ReferentialIntegrityViolationException {
        if (node.getParent() != null) {
            Optional<T> oParent = findById(node.getParent().getEntityId());
            if (oParent.isPresent()) {
                oParent.get().getDescendants().remove(node.getId());
                super.save(datastore, oParent.get());
            }
        }
        return super.delete(datastore, node);
    }

    @Override
    public T save(@Valid T value) {
        T saved;
        // create a transactional session
        try (MorphiaSession session = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).startSession()) {
            // if updating, and parent changed, remove from old parent's descendants
            if (value.getId() != null) {
                T existing = this.findById(value.getId()).orElse(null);
                if (existing != null && existing.getParent() != null) {
                    ObjectId oldParentId = existing.getParent().getEntityId();
                    ObjectId newParentId = (value.getParent() != null) ? value.getParent().getEntityId() : null;
                    if (!Objects.equals(oldParentId, newParentId)) {
                        Optional<T> oOldParent = this.findById(oldParentId);
                        if (oOldParent.isPresent()) {
                            oOldParent.get().getDescendants().remove(existing.getId());
                            super.save(session, oOldParent.get());
                        }
                    }
                }
            }

            // Validate parent assignment (no self-parenting, no cycles, parent exists)
            if (value.getParent() != null) {
                ObjectId newParentId = value.getParent().getEntityId();
                if (newParentId == null) {
                    throw new NotFoundException("Parent id is null");
                }

                // Parent must exist
                this.findById(newParentId)
                        .orElseThrow(() -> new NotFoundException("Parent node not found for id: " + newParentId));

                // Self-parenting check
                if (value.getId() != null && value.getId().equals(newParentId)) {
                    throw new IllegalArgumentException("Invalid hierarchy: a node cannot be its own parent (id=" + value.getId() + ")");
                }

                // Cycle check: parent must not be a descendant of value
                if (value.getId() != null) {
                    ObjectId cursor = newParentId;
                    while (cursor != null) {
                        if (cursor.equals(value.getId())) {
                            throw new IllegalArgumentException(
                                    "Invalid hierarchy: setting parent to a descendant would create a cycle (node="
                                            + value.getId() + ", parent=" + newParentId + ")");
                        }
                        Optional<T> p = findById(cursor);
                        if (!p.isPresent() || p.get().getParent() == null) {
                            break; // reached root
                        }
                        cursor = p.get().getParent().getEntityId();
                    }
                }
            }

            // Persist the node
            saved = super.save(session, value);

            // Ensure parent's descendants include this node
            if (saved.getParent() != null) {
                Optional<T> oParent = findById(saved.getParent().getEntityId());
                if (!oParent.isPresent()) {
                    throw new NotFoundException("Parent node not found for id: " + saved.getParent().getEntityId());
                }
                T parent = oParent.get();
                if (parent.getDescendants() == null) {
                    parent.setDescendants(new ArrayList<>());
                }
                if (!parent.getDescendants().contains(saved.getId())) {
                    parent.getDescendants().add(saved.getId());
                    super.save(session, parent);
                } else {
                    Log.warnf("Child node id: %s already exists as a child of node id: %s", saved.getId().toHexString(), parent.getId().toHexString());
                }
            }
            return saved;
        }
    }

    public List<T> getAllChildrenByRefName(String refName) {
        Optional<T> oHierarchyNode = findByRefName(refName);
        if (!oHierarchyNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for refName: " + refName);
        }
        return getAllChildren(oHierarchyNode.get().getId());
    }

    public List<com.e2eq.framework.model.TreeNode> getTrees() {
        List<com.e2eq.framework.model.TreeNode> nodes = new ArrayList<>();
        Document query = new Document("parent", new Document("$exists", false));
        try (MongoCursor<T> cursor = getMorphiaDataStore().getCollection(getPersistentClass()).find(query).iterator()) {
            while (cursor.hasNext()) {
                T root = cursor.next();
                nodes.add(resolveToHierarchy(root));
            }
        }

        return nodes;
    }

    private com.e2eq.framework.model.TreeNode toTreeNode(T object) {
        com.e2eq.framework.model.TreeNode node = new com.e2eq.framework.model.TreeNode();
        node.key = object.getId().toHexString();
        node.label = object.getDisplayName();
        node.icon = "pi pi-map-marker";
        return node;
    }

    private com.e2eq.framework.model.TreeNode resolveToHierarchy(T object) {
        // Convert object to TreeNode
        com.e2eq.framework.model.TreeNode node = toTreeNode(object);

        // Populate curated metadata only (avoid leaking internal fields)
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("id", node.key);
        data.put("displayName", object.getDisplayName());
        data.put("skipValidation", false);
        // TODO should pull this from functional domain definition
        data.put("defaultUIActions", List.of("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE"));
        node.data = data;

        // Guard against accidental cycles
        return resolveChildren(object, node, new java.util.HashSet<>());
    }

    private com.e2eq.framework.model.TreeNode resolveChildren(T object, com.e2eq.framework.model.TreeNode node, java.util.Set<ObjectId> visited) {
        if (object.getId() != null && !visited.add(object.getId())) {
            // already visited, break the cycle
            return node;
        }

        // Handle child nodes with one batch fetch per level
        if (object.getDescendants() != null && !object.getDescendants().isEmpty()) {
            List<ObjectId> ids = object.getDescendants();
            List<T> children = getMorphiaDataStore().find(getPersistentClass())
                                  .filter(dev.morphia.query.filters.Filters.in("_id", ids))
                                  .iterator().toList();
            java.util.Map<ObjectId, T> byId = new java.util.HashMap<>();
            for (T c : children) {
                if (c.getId() != null) {
                    byId.put(c.getId(), c);
                }
            }
            for (ObjectId id : ids) {
                T childObject = byId.get(id);
                if (childObject != null) {
                    com.e2eq.framework.model.TreeNode childNode = toTreeNode(childObject);
                    // Curated child data
                    java.util.Map<String, Object> childData = new java.util.HashMap<>();
                    childData.put("id", childNode.key);
                    childData.put("displayName", childObject.getDisplayName());
                    childData.put("skipValidation", false);
                    childData.put("defaultUIActions", List.of("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE"));
                    childNode.data = childData;

                    node.children.add(resolveChildren(childObject, childNode, visited));
                }
            }
        }
        return node;
    }


    public List<T> getAllChildren(ObjectId nodeId) {
        // Start the pipeline on the hierarchy collection for this entity class
        Class<T> entityClass = getPersistentClass();
        Aggregation<T> pipeline = morphiaDataStoreWrapper
                .getDataStore(getSecurityContextRealmId())
                .aggregate(entityClass)
                .match(eq("_id", nodeId))
                .graphLookup(
                        graphLookup(entityClass)
                                .startWith("$descendants")
                                .connectFromField("descendants")
                                .connectToField("_id")
                                .as("children")
                        // No .maxDepth() => unlimited depth
                );

        // Execute and return the list of child documents
        MongoCursor<T> cursor = pipeline.execute(entityClass);
        while (cursor.hasNext()) {
            T t = cursor.next();
            List<T> children = t.getChildren();
            return children;
        }

        return new ArrayList<>();
    }

    /**
     * Gets all objects for the hierarchy starting from a node by refName.
     * By default, filters are accumulated along the path (parent filter A AND child filter B).
     *
     * @param refName the refName of the starting hierarchy node
     * @return list of objects with accumulated filters applied
     */
    public List<O> getAllObjectsForHierarchy(String refName) {
        return getAllObjectsForHierarchy(refName, false);
    }

    /**
     * Gets all objects for the hierarchy starting from a node by refName.
     *
     * @param refName the refName of the starting hierarchy node
     * @param skipFilterAccumulation if true, each node's filter is applied independently (legacy behavior);
     *                               if false (default), filters are accumulated along the path
     * @return list of objects
     */
    public List<O> getAllObjectsForHierarchy(String refName, boolean skipFilterAccumulation) {
        Optional<T> oHierarchyNode = findByRefName(refName);
        if (!oHierarchyNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for refName: " + refName);
        }

        if (skipFilterAccumulation) {
            // Legacy behavior: each node's filter applied independently
            List<O> objects = new ArrayList<>();
            Set<O> objectSet = new HashSet<>();
            if (oHierarchyNode.get().getStaticDynamicList() != null) {
                StaticDynamicList<O> staticDynamicList = oHierarchyNode.get().getStaticDynamicList();
                objectSet.addAll(objectListRepo.resolveItems(staticDynamicList, new ArrayList<>()));
            }

            List<T> descendants = getAllChildren(oHierarchyNode.get().getId());
            if (!descendants.isEmpty()) {
                for (T t : descendants) {
                    StaticDynamicList<O> objectList = t.getStaticDynamicList();
                    if (objectList == null) {
                        continue;
                    }
                    objectSet.addAll(objectListRepo.getObjectsForList(objectList, new ArrayList<>()));
                }
                objects.addAll(objectSet);
            }
            return objects;
        } else {
            // New behavior: accumulated filters along the path
            return getAllObjectsForHierarchyWithAccumulatedFilters(oHierarchyNode.get().getId());
        }
    }

    /**
     * Gets all objects for the hierarchy starting from a node.
     * By default, filters are accumulated along the path.
     *
     * @param node the starting hierarchy node
     * @return list of objects with accumulated filters applied
     */
    protected List<O> getAllObjectsForHierarchy(@Valid T node) {
        return getAllObjectsForHierarchy(node, false);
    }

    /**
     * Gets all objects for the hierarchy starting from a node.
     *
     * @param node the starting hierarchy node
     * @param skipFilterAccumulation if true, each node's filter is applied independently (legacy behavior);
     *                               if false (default), filters are accumulated along the path
     * @return list of objects
     */
    protected List<O> getAllObjectsForHierarchy(@Valid T node, boolean skipFilterAccumulation) {
        Objects.requireNonNull(node, "node can not be null for getAllObjectsForHierarchy method");
        Objects.requireNonNull(node.getId(), "node id can not be null for getAllObjectsForHierarchy method");
        Optional<T> oNode = findById(node.getId());
        if (!oNode.isPresent()) {
            throw new NotFoundException("Node not found for id: " + node.getId());
        }

        if (skipFilterAccumulation) {
            // Legacy behavior: each node's filter applied independently
            Set<O> locationSet = new HashSet<>();
            List<T> descendants = getAllChildren(node.getId());
            if (descendants != null && !descendants.isEmpty()) {
                for (T t : descendants) {
                    StaticDynamicList<O> objectList = t.getStaticDynamicList();
                    if (objectList == null) {
                        continue;
                    }
                    locationSet.addAll(objectListRepo.getObjectsForList(objectList, new ArrayList<>()));
                }
            }
            return new ArrayList<>(locationSet);
        } else {
            // New behavior: accumulated filters along the path
            return getAllObjectsForHierarchyWithAccumulatedFilters(node.getId());
        }
    }

    /**
     * Gets all objects for the hierarchy starting from a node by ObjectId.
     * By default, filters are accumulated along the path.
     *
     * @param objectId the id of the starting hierarchy node
     * @return list of objects with accumulated filters applied
     */
    public List<O> getAllObjectsForHierarchy(ObjectId objectId) {
        return getAllObjectsForHierarchy(objectId, false);
    }

    /**
     * Gets all objects for the hierarchy starting from a node by ObjectId.
     *
     * @param objectId the id of the starting hierarchy node
     * @param skipFilterAccumulation if true, each node's filter is applied independently (legacy behavior);
     *                               if false (default), filters are accumulated along the path
     * @return list of objects
     */
    public List<O> getAllObjectsForHierarchy(ObjectId objectId, boolean skipFilterAccumulation) {
        Optional<T> ohiearchyNode = findById(objectId);
        if (!ohiearchyNode.isPresent()) {
            throw new NotFoundException("Hierarchy Node not found for id: " + objectId);
        }
        return getAllObjectsForHierarchy(ohiearchyNode.get(), skipFilterAccumulation);
    }

    /**
     * Visits each child in the hierarchy starting with the supplied identifier.
     *
     * @param objectId the id of the hierarchy node to start traversal from
     * @param visitor  the visitor that will be invoked for each child id
     */
    public void visitHierarchy(ObjectId objectId, HierarchyVisitor<T> visitor) {
        Objects.requireNonNull(objectId, "objectId can not be null for visitHierarchy method");
        Objects.requireNonNull(visitor, "visitor can not be null for visitHierarchy method");
        List<T> children = getAllChildren(objectId);
        if (children != null) {
            for (T child : children) {
                if (child != null && child.getId() != null) {
                    visitor.visit(child);
                }
            }
        }
    }

    @FunctionalInterface
    public interface HierarchyVisitor<T> {
        void visit(T  child);
    }

    /**
     * Builds the path from the root to the specified node, ordered from root to node.
     * This is useful for accumulating filters along the hierarchy path.
     *
     * @param nodeId the id of the node to build the path to
     * @return list of hierarchy nodes from root to the specified node (inclusive)
     */
    public List<T> getPathToNode(ObjectId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId cannot be null for getPathToNode method");

        List<T> path = new ArrayList<>();
        ObjectId currentId = nodeId;

        // Walk up the tree from node to root
        while (currentId != null) {
            Optional<T> oNode = findById(currentId);
            if (!oNode.isPresent()) {
                break;
            }
            T node = oNode.get();
            path.add(node);

            if (node.getParent() == null) {
                break; // reached root
            }
            currentId = node.getParent().getEntityId();
        }

        // Reverse to get root-to-node order
        Collections.reverse(path);
        return path;
    }

    /**
     * Builds a list of filter strings from the path to the specified node.
     * Only includes non-null, non-empty filter strings from dynamic lists.
     *
     * @param nodeId the id of the node
     * @return list of filter strings from root to node (only includes non-empty filters)
     */
    public List<String> getFilterPathToNode(ObjectId nodeId) {
        List<T> path = getPathToNode(nodeId);
        return path.stream()
                .filter(node -> node.getStaticDynamicList() != null)
                .filter(node -> node.getStaticDynamicList().isDynamic())
                .map(node -> node.getStaticDynamicList().getFilterString())
                .filter(filter -> filter != null && !filter.trim().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Combines multiple filter strings using AND logic.
     * Each filter is wrapped in parentheses to ensure proper precedence.
     *
     * @param filters list of filter strings to combine
     * @return combined filter string, or null if no filters provided
     */
    public String combineFilters(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        // Wrap each filter in parentheses and join with AND
        return filters.stream()
                .map(f -> "(" + f + ")")
                .collect(Collectors.joining(" && "));
    }

    /**
     * Gets the accumulated filter string for a node, combining all filters
     * from the root down to and including this node.
     *
     * @param nodeId the id of the node
     * @return the combined filter string, or null if no dynamic filters exist in the path
     */
    public String getAccumulatedFilterForNode(ObjectId nodeId) {
        List<String> filterPath = getFilterPathToNode(nodeId);
        return combineFilters(filterPath);
    }

    /**
     * Gets objects for a hierarchy node with accumulated filters from the path.
     * If the node has a dynamic list, the filter applied will be the combination
     * of all parent filters AND this node's filter.
     *
     * @param nodeId the id of the hierarchy node
     * @return list of objects matching the accumulated filter criteria
     */
    public List<O> getObjectsWithAccumulatedFilter(ObjectId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");

        Optional<T> oNode = findById(nodeId);
        if (!oNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for id: " + nodeId);
        }

        T node = oNode.get();
        StaticDynamicList<O> staticDynamicList = node.getStaticDynamicList();

        if (staticDynamicList == null) {
            return new ArrayList<>();
        }

        if (staticDynamicList.isStatic()) {
            // For static lists, we need to filter the items using accumulated parent filters
            List<O> items = staticDynamicList.getItems();
            if (items == null || items.isEmpty()) {
                return new ArrayList<>();
            }

            // Get accumulated filter from parents (excluding this node since it's static)
            List<T> path = getPathToNode(nodeId);
            // Remove the last element (current node) as it's static
            if (path.size() > 1) {
                path = path.subList(0, path.size() - 1);
            } else {
                // No parents, return items as-is
                return new ArrayList<>(items);
            }

            List<String> parentFilters = path.stream()
                    .filter(n -> n.getStaticDynamicList() != null)
                    .filter(n -> n.getStaticDynamicList().isDynamic())
                    .map(n -> n.getStaticDynamicList().getFilterString())
                    .filter(f -> f != null && !f.trim().isEmpty())
                    .collect(Collectors.toList());

            if (parentFilters.isEmpty()) {
                return new ArrayList<>(items);
            }

            // Apply parent filters to the static items by querying with both the
            // parent filter and an IN clause for the static item IDs
            List<ObjectId> itemIds = items.stream()
                    .map(UnversionedBaseModel::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (itemIds.isEmpty()) {
                return new ArrayList<>();
            }

            String parentFilter = combineFilters(parentFilters);
            String idFilter = "_id:in:[" + itemIds.stream()
                    .map(id -> "\"" + id.toHexString() + "\"")
                    .collect(Collectors.joining(",")) + "]";

            String combinedFilter = "(" + parentFilter + ") && (" + idFilter + ")";
            return objectRepo.getListByQuery(0, -1, combinedFilter, null, null);

        } else if (staticDynamicList.isDynamic()) {
            // For dynamic lists, combine all filters from path
            String accumulatedFilter = getAccumulatedFilterForNode(nodeId);
            if (accumulatedFilter == null || accumulatedFilter.trim().isEmpty()) {
                return new ArrayList<>();
            }
            return objectRepo.getListByQuery(0, -1, accumulatedFilter, null, null);
        }

        return new ArrayList<>();
    }

    /**
     * Gets objects for a hierarchy node by refName with accumulated filters from the path.
     *
     * @param refName the refName of the hierarchy node
     * @return list of objects matching the accumulated filter criteria
     */
    public List<O> getObjectsWithAccumulatedFilter(String refName) {
        Optional<T> oNode = findByRefName(refName);
        if (!oNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for refName: " + refName);
        }
        return getObjectsWithAccumulatedFilter(oNode.get().getId());
    }

    /**
     * Gets all objects for the entire hierarchy starting from a node,
     * with filters properly accumulated along each path.
     * Unlike getAllObjectsForHierarchy, this method ensures that objects
     * at each level satisfy ALL filters from the root to that level.
     *
     * @param nodeId the id of the starting hierarchy node
     * @return list of objects with proper filter accumulation
     */
    public List<O> getAllObjectsForHierarchyWithAccumulatedFilters(ObjectId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");

        Optional<T> oNode = findById(nodeId);
        if (!oNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for id: " + nodeId);
        }

        Set<O> objectSet = new HashSet<>();

        // Get objects for the starting node with its accumulated filters
        objectSet.addAll(getObjectsWithAccumulatedFilter(nodeId));

        // Get all descendants and collect their objects with accumulated filters
        List<T> descendants = getAllChildren(nodeId);
        for (T descendant : descendants) {
            if (descendant.getId() != null) {
                try {
                    objectSet.addAll(getObjectsWithAccumulatedFilter(descendant.getId()));
                } catch (Exception e) {
                    Log.warnf("Error getting objects for descendant %s: %s",
                            descendant.getId().toHexString(), e.getMessage());
                }
            }
        }

        return new ArrayList<>(objectSet);
    }

    /**
     * Gets all objects for the entire hierarchy starting from a node by refName,
     * with filters properly accumulated along each path.
     *
     * @param refName the refName of the starting hierarchy node
     * @return list of objects with proper filter accumulation
     */
    public List<O> getAllObjectsForHierarchyWithAccumulatedFilters(String refName) {
        Optional<T> oNode = findByRefName(refName);
        if (!oNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for refName: " + refName);
        }
        return getAllObjectsForHierarchyWithAccumulatedFilters(oNode.get().getId());
    }

}
