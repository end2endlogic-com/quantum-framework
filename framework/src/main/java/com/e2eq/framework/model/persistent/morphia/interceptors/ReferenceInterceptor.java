package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;

import dev.morphia.Datastore;
import dev.morphia.EntityListener;

import dev.morphia.annotations.Reference;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;
import java.lang.annotation.Annotation;
import java.util.Collection;


@ApplicationScoped
public class ReferenceInterceptor implements EntityListener<Object> {


    public ReferenceInterceptor() {
        // Default constructor
    }

    @Override
    public void prePersist (Object childEntity, Document childDocument, Datastore datastore) {
        // The entity is the class that is about to be persisted
        // The document is the BSON document that will be written to the database
        // The datastore is the Datastore instance that is handling the persistence


        Mapper mapper = datastore.getMapper();
        // Get the mapped class information for the class that is being persisted
        EntityModel child = mapper.getEntityModel(childEntity.getClass());


        // Iterate over all the properties of the class that is being persisted
        for (PropertyModel childField : child.getProperties()) {
            // if the class is annotated as a reference
            if (childField.isReference()) {
                Class parentClass = childField.getAccessor().get(childEntity).getClass();

                // check if the parent is to another BaseModel
                if (BaseModel.class.isAssignableFrom(parentClass)) {
                    // So the reference is to another base model
                    BaseModel parentBaseModel = (BaseModel) childField.getAccessor().get(childEntity);

                    if (parentBaseModel != null) {
                        if (BaseModel.class.isAssignableFrom(childEntity.getClass())){
                            ReferenceEntry entry = new ReferenceEntry(((BaseModel)childEntity).getId(), childField.getEntityModel().getType().getTypeName());
                            if (!parentBaseModel.getReferences().contains(entry)){
                                parentBaseModel.getReferences().add(entry);
                                datastore.save(childField.getAccessor().get(childEntity));
                            }
                        }
                    } else {
                        Reference ref = childField.getAnnotation(Reference.class);
                        if (!ref.ignoreMissing()) {
                            throw new IllegalStateException("Reference field " + childField.getName() + " is null" + " but is marked ignoreMissing false remove reference or add correct parent prior to saving");
                        }
                    }
                } else
                // check if the reference is to a list of BaseModels
                if (Collection.class.isAssignableFrom(childField.getAccessor().get(childEntity).getClass())) {
                    Collection<BaseModel> parentBaseModels = (Collection<BaseModel>) childField.getAccessor().get(childEntity);
                    if (parentBaseModels != null) {
                        for (BaseModel parentBaseModel : parentBaseModels) {
                            if (BaseModel.class.isAssignableFrom(childEntity.getClass())){
                               // ReferenceEntry entry = new ReferenceEntry(parentBaseModel.getId(), parentBaseModel.getClass().getTypeName());
                                ReferenceEntry entry = new ReferenceEntry(((BaseModel)childEntity).getId(), childField.getEntityModel().getType().getTypeName());
                                if (!parentBaseModel.getReferences().contains(entry)){
                                    parentBaseModel.getReferences().add(entry);
                                    datastore.save(parentBaseModel);
                                }
                            }
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
