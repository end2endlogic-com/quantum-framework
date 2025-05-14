package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.BaseModel;
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

// T - The hiearchical Model
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
        T extends HierarchicalModel,
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
                Optional<T> oParent = findById(existing.getParent().getId());
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
    public long delete(@NotNull("the datastore must not be null") Datastore datastore, T aobj) throws ReferentialIntegrityViolationException {
        if (aobj.getParent() != null) {
            Optional<T> oParentTerritory = findById(aobj.getParent().getId());
            if (oParentTerritory.isPresent()) {
                oParentTerritory.get().getDescendants().remove(aobj.getId());
                super.save(datastore,oParentTerritory.get());
            }
        }
        return super.delete(datastore, aobj);
    }

    @Override
    public T save(@Valid T value) {
        T saved = null;
        // create a transactional session
        try (MorphiaSession session = morphiaDataStore.getDataStore(getSecurityContextRealmId()).startSession()) {
            // check if the territory already exists
            // assuming the value has a id this is an update to the object
            if (value.getId() != null) {
                // get the existing territory from the database
                T existing = this.findById(value.getId()).orElse(null);
                if (existing != null && existing.getParent() != null && !existing.getParent().getId().equals(value.getParent().getId())) {
                    // remove the territory from the old parent
                    Optional<T> ooldParent = this.findById(existing.getParent().getId());
                    if (ooldParent.isPresent()) {
                        ooldParent.get().getDescendants().remove(existing.getId());
                        super.save(session, ooldParent.get());
                    }
                }
            }
            saved = super.save(session, value);
            // check if there is a parent territory
            if (saved.getParent() != null) {
                // get the parent territory
                Optional<T> oparent = findById(saved.getParent().getId());
                if (!oparent.isPresent()) {
                    throw new NotFoundException("Parent territory not found for id: " + saved.getParent().getId());
                }
                T parent = oparent.get();
                if (parent.getDescendants() == null) {
                    parent.setDescendants(new ArrayList<>());
                    parent.getDescendants().add(value.getId());
                    super.save(session, parent);
                } else if (!parent.getDescendants().contains(value.getId())) {
                    parent.getDescendants().add(saved.getId());
                    super.save(session, parent);
                } else {
                    Log.warnf("Child territory id: %s already exists as a child of territory id: %s", saved.getId().toHexString(), parent.getId().toHexString());
                }
            }
            return saved;
        }
    }


    public List<T> getAllChildren(ObjectId territoryId) {
        // Start the pipeline on the "territory" collection
        Class<T> entityClass = getPersistentClass();
        Aggregation<T> pipeline = morphiaDataStore
                .getDataStore(getSecurityContextRealmId())
                .aggregate(entityClass)
                .match(eq("_id", territoryId))
                .graphLookup(
                        graphLookup(entityClass)
                                .startWith("$descendants")
                                .connectFromField("descendants")
                                .connectToField("_id")
                                .as("children")
                        // No .maxDepth() => unlimited depth
                );


        // Execute and return the list of Territory documents
        MongoCursor<T> cursor = pipeline.execute(entityClass);
        while (cursor.hasNext()) {
            T t = cursor.next();
            List<T> children = t.getChildren();
            return children;
        }

        return new ArrayList<>();
    }

    public List<O> getAllObjectsForHierarchy(String refName) {
        Optional<T> ohiearchicalObject = findByRefName(refName);
        if (!ohiearchicalObject.isPresent()) {
            throw new NotFoundException("Territory not found for refName: " + refName);
        }
        List<O> objects = new ArrayList<>();
        if (ohiearchicalObject.isPresent()) {
            HashSet<O> objectSet = new HashSet<>();
            if (ohiearchicalObject.get().getStaticDynamicList() != null) {
                StaticDynamicList<O> staticDynamicList = ohiearchicalObject.get().getStaticDynamicList();
                objectSet.addAll(objectListRepo.resolveItems(staticDynamicList, new ArrayList<>()));
            }

            List<T> decendents = getAllChildren(ohiearchicalObject.get().getId());
            if (!decendents.isEmpty()) {
                for (T t : decendents) {
                   StaticDynamicList<O> objectList = t.getStaticDynamicList();
                    if (objectList == null) {
                        continue;
                    }
                    objectSet.addAll(objectListRepo.getObjectsForList(objectList, new ArrayList<O>()));
                }
                objects.addAll(objectSet);
            }
        }
        return objects;
    }

    protected List<O> getAllObjectsForHierarchy(@Valid T territory) {
        Objects.requireNonNull(territory, "territory can not be null for getAllLocationsForTerritory method");
        Objects.requireNonNull(territory.getId(), "territory id can not be null for getAllLocationsForTerritory method");
        Optional<T> oterritory = findById(territory.getId());
        if (!oterritory.isPresent()) {
            throw new NotFoundException("Territory not found for id: " + territory.getId());
        }
        Set<O> locationSet = new HashSet<>();
        List<T> descendants = getAllChildren(territory.getId());
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
            throw new NotFoundException("Hiearchy Node not found for id: " + objectId);
        }
        return getAllObjectsForHierarchy(ohiearchyNode.get());
    }

}
