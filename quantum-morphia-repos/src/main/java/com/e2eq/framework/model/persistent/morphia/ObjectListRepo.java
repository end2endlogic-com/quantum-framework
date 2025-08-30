package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.Datastore;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.lang3.NotImplementedException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * This is a base class that implements base functionality using Morphia as
 * the library to store classes in Mongodb
 * @param <O> - The object type for the list
 * @param <T> - the static dynamic list type
 * @param <OR> - the object repo type
 */
public class ObjectListRepo<
        O extends UnversionedBaseModel,
        T extends StaticDynamicList<O>,
        OR extends MorphiaRepo<O>> extends MorphiaRepo<T> {

    @ConfigProperty(name="quantum.staticDynamicList.check-ids", defaultValue= "false")
    boolean checkIds;

    @Inject
    OR objectRepo;

    @Override
    public T save(@NotNull Datastore datastore, @Valid T value) {
        if (value.isDynamic() && checkIds && value.getItems() != null && !value.getItems().isEmpty()) {
            for (O item: value.getItems()) {
                if (objectRepo.findById(item.getId()).isEmpty()) {
                    throw new NotFoundException(String.format("Object with id %s not found saving objectList with id:%s",
                            item.getId().toHexString(), value.getId().toHexString()));
                }
            }
        }
        return super.save(datastore, value);
    }

    public List<O> getObjectsForFilterString(String filterString, List<O> objects) {
        Objects.requireNonNull(filterString, "filterString can not be null for getObjectsForFilterString method");
        Objects.requireNonNull(objects, "objects can not be null for getObjectsForFilterString method");
        objects.addAll(objectRepo.getListByQuery(0, -1, filterString, null, null));
        return objects;
    }

    public List<O> getObjectsForList(StaticDynamicList<O> staticDynamicList, List<O> objects) {
        if (staticDynamicList.isStatic()) {
            objects = staticDynamicList.getItems();
        } else if (staticDynamicList.isDynamic()) {
            String filterString = staticDynamicList.getFilterString();
            objects = objectRepo.getListByQuery(0, -1, filterString, null, null);
        } else {
            throw new NotImplementedException(String.format("Unsupported location list type: %s ", staticDynamicList.getMode()));
        }
        return objects;
    }

    public List<O> resolveItems(StaticDynamicList<O> staticDynamicList, List<O> objects) {
        if( staticDynamicList.isDynamic()) {
            return getObjectsForFilterString(staticDynamicList.getFilterString(), objects);
        } else if(staticDynamicList.isStatic()) {
            return getObjectsForList(staticDynamicList, objects);
        } else {
            throw new NotImplementedException(String.format("Unsupported location list type: %s ", staticDynamicList.getMode()));
        }
    }
}
