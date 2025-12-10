package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.mongodb.client.MongoCursor;
import dev.morphia.Datastore;
import dev.morphia.aggregation.Aggregation;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.NotFoundException;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import java.util.*;

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

    public List<O> getAllObjectsForHierarchy(String refName) {
        Optional<T> oHierarchyNode = findByRefName(refName);
        if (!oHierarchyNode.isPresent()) {
            throw new NotFoundException("Hierarchy node not found for refName: " + refName);
        }
        List<O> objects = new ArrayList<>();
        if (oHierarchyNode.isPresent()) {
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
        }
        return objects;
    }

    protected List<O> getAllObjectsForHierarchy(@Valid T node) {
        Objects.requireNonNull(node, "node can not be null for getAllObjectsForHierarchy method");
        Objects.requireNonNull(node.getId(), "node id can not be null for getAllObjectsForHierarchy method");
        Optional<T> oNode = findById(node.getId());
        if (!oNode.isPresent()) {
            throw new NotFoundException("Node not found for id: " + node.getId());
        }
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
    }

    public List<O> getAllObjectsForHierarchy(ObjectId objectId) {
        Optional<T> ohiearchyNode = findById(objectId);
        if (!ohiearchyNode.isPresent()) {
            throw new NotFoundException("Hierarchy Node not found for id: " + objectId);
        }
        return getAllObjectsForHierarchy(ohiearchyNode.get());
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

}
