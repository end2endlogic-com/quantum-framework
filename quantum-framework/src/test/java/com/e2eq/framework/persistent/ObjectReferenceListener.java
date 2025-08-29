package com.e2eq.framework.persistent;

import com.e2eq.framework.annotations.ObjectReference;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.test.ObjectRefModel;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.MorphiaDatastore;
import dev.morphia.annotations.PostLoad;
import dev.morphia.annotations.PostPersist;
import dev.morphia.annotations.PrePersist;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Instance;
import org.bson.Document;
import jakarta.enterprise.inject.spi.CDI;

import java.lang.annotation.Annotation;
import java.util.Optional;

public class ObjectReferenceListener implements EntityListener<ObjectRefModel> {

    @Override
    @PostLoad
    public void postLoad(ObjectRefModel entity, Document document, Datastore datastore) {
        // check the if the datastore is an instance of MorphiaDatastore using is assignable from
        if (datastore.getClass().isAssignableFrom(MorphiaDatastore.class) &&
          entity instanceof UnversionedBaseModel) {
            Mapper mapper = ((MorphiaDatastore)datastore).getMapper();

            EntityModel entityModel = mapper.getEntityModel(entity.getClass());
            for (PropertyModel property : entityModel.getProperties()) {
                if (property.hasAnnotation(ObjectReference.class)) {
                    if ( document.get(property.getMappedName()) instanceof Document  )
                    {
                        String refName = ((Document) document.get(property.getMappedName())).getString("refName");
                        Log.info("*** postload:" + refName);
                        // get the DAO for the associated class
                        try {
                            // Use the fully qualified name of the class
                            Class<? extends MorphiaRepo<? extends UnversionedBaseModel>> daoClass =
                                    (Class<? extends MorphiaRepo<? extends UnversionedBaseModel>>) Class.forName("com.e2eq.framework.persistent.TestParentRepo");
                            Instance<? extends MorphiaRepo<? extends UnversionedBaseModel>> instance = CDI.current().select(daoClass);
                            if (!instance.isUnsatisfied()) {
                                MorphiaRepo<? extends UnversionedBaseModel> repo = instance.get();
                                Optional<? extends UnversionedBaseModel> obaseModel = repo.findByRefName(refName);
                                if (obaseModel.isPresent()) {
                                    UnversionedBaseModel baseModel = obaseModel.get();
                                    property.getAccessor().set(entity, baseModel);
                                }
                                // Use the repo instance as needed
                            } else {
                                Log.warn("No bean found for class: " + refName);
                            }
                        } catch (ClassNotFoundException e) {
                            Log.error("Class not found: " + refName, e);
                        }
                    }
                    // Load the associated class using refName
                    // Set the loaded object to the entity's field
                }
            }
        }
    }

    @Override
    @PostPersist
    public void postPersist(ObjectRefModel entity, Document document, Datastore datastore) {

    }

    @Override
    @PrePersist
    public void prePersist(ObjectRefModel entity, Document document, Datastore datastore) {
        if (datastore.getClass().isAssignableFrom(MorphiaDatastore.class)) {
            Mapper mapper = ((MorphiaDatastore)datastore).getMapper();
            EntityModel entityModel = mapper.getEntityModel(entity.getClass());
            for (PropertyModel property : entityModel.getProperties()) {
                if (property.hasAnnotation(ObjectReference.class)) {
                    // Get the object from the entity's field
                    // Replace it with a RefNameReference object
                    // Update the document with the refName
                    Log.info("*** prePersist: Property:" + property.getMappedName() + "- Entity:" + entity.getClass().getSimpleName() );

                    // retrieve the value fro the field
                    Object value = property.getAccessor().get(entity);
                    if (value != null && value instanceof UnversionedBaseModel) {
                        UnversionedBaseModel baseModel = (UnversionedBaseModel) value;
                        Document document1 = createRefNameDocument((UnversionedBaseModel) value);
                        document.put(property.getMappedName(), document1);
                        property.getAccessor().set(entity, null);
                    }
                    else {
                        Log.info("*** prePersist: value is null");
                    }

                }
            }
        } else {
            Log.info("*** prePersist: Not MorphiaDatastore");
        }
    }

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        //boolean t =  ObjectReference.class.equals(type);
        //Log.info("*** hasAnnotation:" + t);
        return false;
    }


    /**
     * Create Document with refName, dataDomain and displayName from BaseModel
     */
    private Document createRefNameDocument(UnversionedBaseModel model) {
        Document refNameDocument = new Document();
        refNameDocument.put("refName", model.getRefName());
        refNameDocument.put("displayName", model.getDisplayName());
        refNameDocument.put("_t", model.getClass().getSimpleName() );
        return refNameDocument;
    }

    // Implement other methods as needed
}
