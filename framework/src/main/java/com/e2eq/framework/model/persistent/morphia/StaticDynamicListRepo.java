package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.NotFoundException;
import org.bson.types.ObjectId;

import java.util.*;


/**
 *
 * @param <O> - the object that is in the list
 * @param <OR> - the repo for that object
 * @param <T> - The static dynamic list type
 */
public abstract class StaticDynamicListRepo<O extends UnversionedBaseModel, OR extends MorphiaRepo<O>, T extends StaticDynamicList> extends MorphiaRepo<T>{
    OR objectRepo;

    public Set<O> getChildObjectsForList(String refName) {
        Optional<T> ostaticDynamicList = findByRefName(refName);
        HashSet<O> objects = new HashSet<>();
        if (ostaticDynamicList.isPresent()) {
            T staticDynamicList = ostaticDynamicList.get();

            if (staticDynamicList.getMode() == StaticDynamicList.Mode.STATIC) {
               staticDynamicList.getItems().add( staticDynamicList.getItems() );
            } else if (staticDynamicList.getMode() == StaticDynamicList.Mode.DYNAMIC) {
                objectRepo.getListByQuery(0,-1, staticDynamicList.getFilterString());
            } else {
                throw new UnsupportedOperationException(String.format("Location List with mode: %s is not supported" , staticDynamicList.getMode()));
            }

        } else {
            throw new NotFoundException("Location List not found for refName: " + refName);
        }
        return objects;
    }

    public List<O> getChildObjectsForList(ObjectId id) {
        Optional<T> ostaticDynamicList = findById(id);
        if (ostaticDynamicList.isPresent()) {
            T staticDynamicList = ostaticDynamicList.get();
            if (staticDynamicList.getMode() == StaticDynamicList.Mode.STATIC) {

                return new ArrayList<>(staticDynamicList.getItems() );
            } else {
                return Collections.emptyList();
            }
        } else {
            throw new NotFoundException("Location List not found for id: " + id);
        }
    }

    public List<O> getObjectsForList(@NotNull(message = "locationList can not be null for getLocationsForList method")  T staticDynamicList) {
        List<O> objects = null;
        if (staticDynamicList.getMode() == StaticDynamicList.Mode.STATIC) {
            objects = staticDynamicList.getItems();
        } else if (staticDynamicList.getMode() == StaticDynamicList.Mode.DYNAMIC) {
            objects = objectRepo.getListByQuery(0,-1, staticDynamicList.getFilterString());
        } else {
            throw new UnsupportedOperationException(String.format("Location List with mode: %s is not supported" , staticDynamicList.getMode()));
        }
        return objects;
    }


}
