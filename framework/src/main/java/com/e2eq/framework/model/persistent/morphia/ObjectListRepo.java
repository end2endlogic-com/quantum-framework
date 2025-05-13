package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import dev.morphia.Datastore;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.lang3.NotImplementedException;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class ObjectListRepo<
        O extends BaseModel,
        T extends StaticDynamicList<O>,
        OR extends MorphiaRepo<O>> extends MorphiaRepo<T> {

    @ConfigProperty(name="quantum.staticDynamicList.check-ids", defaultValue= "false")
    boolean checkIds;

    @Inject
    OR objectRepo;

    @Override
    public T save(@NotNull Datastore datastore, @Valid T value) {
        if (value.isDynamic() && checkIds && value.getStaticIds() != null && !value.getStaticIds().isEmpty()) {
            for (ObjectId iid : value.getStaticIds()) {
                if (objectRepo.findById(iid).isEmpty()) {
                    throw new NotFoundException(String.format("Object with id %s not found saving objectList with id:%s",
                            iid.toHexString(), value.getId().toHexString()));
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
            objects = objectRepo.getListFromIds(staticDynamicList.getStaticIds());
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


    public List<O> resolveItems(T staticDynamicList, Function<String, List<O>> dynamicResolver, Function<List<ObjectId>, List<O>> staticResolver) {
        if (staticDynamicList.isDynamic()) {
            return dynamicResolver.apply(staticDynamicList.getFilterString());
        } else {
            return staticResolver.apply(staticDynamicList.getStaticIds());
        }
    }

}
