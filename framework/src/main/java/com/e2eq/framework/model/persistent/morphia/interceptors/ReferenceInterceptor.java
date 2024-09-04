package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PostPersist;
import dev.morphia.annotations.Reference;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.lang.annotation.Annotation;

@ApplicationScoped
public class ReferenceInterceptor implements EntityListener<Object> {


    @Inject
    protected MorphiaDataStore dataStore;

    public ReferenceInterceptor() {
        // Default constructor
    }

    @Override
    public void prePersist (Object entity, Document document, Datastore datastore) {
        Mapper mapper = dataStore.getDefaultSystemDataStore().getMapper();
        // Get the mapped class information
        EntityModel mappedClass = mapper.getEntityModel(entity.getClass());

        for (PropertyModel mappedField : mappedClass.getProperties()) {
            if (mappedField.isReference()) {
                //Reference ref = mappedField.getAnnotation(Reference.class);
                if (BaseModel.class.isAssignableFrom(mappedField.getEntityModel().getType())) {
                    BaseModel baseModel = (mappedField.getAccessor().get(entity) != null) ?  (BaseModel) mappedField.getAccessor().get(entity) : null;
                    if (baseModel != null) {
                        if (BaseModel.class.isAssignableFrom(entity.getClass() )){
                            ReferenceEntry entry = new ReferenceEntry(((BaseModel)entity).getId(), mappedField.getEntityModel().getType().getTypeName());
                            if (!baseModel.getReferences().contains(entry)){
                                baseModel.getReferences().add(entry);
                                dataStore.getDefaultSystemDataStore().save(mappedField.getAccessor().get(entity));
                            }
                        }


                    } else {
                        Reference ref = mappedField.getAnnotation(Reference.class);
                        if (!ref.ignoreMissing()) {
                            throw new IllegalStateException("Reference field " + mappedField.getName() + " is null" + " but is marked ignoreMissing false remove reference or add correct parent prior to saving");
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return false;
    }
}
