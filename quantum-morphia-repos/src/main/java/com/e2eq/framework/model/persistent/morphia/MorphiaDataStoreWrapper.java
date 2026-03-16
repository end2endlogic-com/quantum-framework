package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.morphia.interceptors.*;

import com.e2eq.framework.util.EnvConfigUtils;
import com.mongodb.client.MongoClient;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import dev.morphia.config.MorphiaConfig;
import dev.morphia.mapping.codec.pojo.EntityModel;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MorphiaDataStoreWrapper {

   @Inject
   MongoClient mongoClient;

   @Inject
   MorphiaDatastore dataStore;
   /*@Inject
   MongoClient mongoClient; */
   @Inject
   PermissionRuleInterceptor permissionRuleInterceptor;
   @Inject
   ValidationInterceptor validationInterceptor;
   @Inject
   AuditInterceptor auditInterceptor;
   @Inject
   ReferenceInterceptor referenceInterceptor;
   @Inject
   PersistenceAuditEventInterceptor persistenceAuditEventInterceptor;

   @Inject
   EnvConfigUtils envConfigUtils;



  // @ConfigProperty(name = "quarkus.mongodb.database") public String databaseName;

   protected Map<String, MorphiaDatastore> datastoreMap = new ConcurrentHashMap<>();

   /** Cached entity types from the base datastore mapper; used to map by class (no package scanning) when creating realm datastores. */
   private volatile List<Class<?>> cachedMappedEntityTypes;

   protected static final boolean ENABLE_ONE_DB_PER_TENANT = false;

   public MorphiaDataStoreWrapper () {
   }

   public Datastore getDefaultSystemDataStore() {
      return getDataStore(envConfigUtils.getSystemRealm());
   }

   public MorphiaDatastore getDataStore(@NotNull String realm) {

      if (realm == null || realm.isEmpty()) {
         throw new IllegalArgumentException("parameter realm needs to be non null and non-empty");
      }

      return datastoreMap.computeIfAbsent(realm, r -> createMorphiaDatastore(r));
   }

   private MorphiaDatastore createMorphiaDatastore(String realm) {
      Log.infof("Creating MorphiaDatastore for realm/database: %s", realm);

      // Clone the MorphiaConfig and override the database
      MorphiaConfig baseConfig = dataStore.getMapper().getConfig();
      MorphiaConfig configForRealm = baseConfig.database(realm);

      MorphiaDatastore mdatastore = new MorphiaDatastore(mongoClient, configForRealm);

      // Add interceptors
      mdatastore.getMapper().addInterceptor(validationInterceptor);
      mdatastore.getMapper().addInterceptor(permissionRuleInterceptor);
      mdatastore.getMapper().addInterceptor(auditInterceptor);
      mdatastore.getMapper().addInterceptor(referenceInterceptor);
      mdatastore.getMapper().addInterceptor(persistenceAuditEventInterceptor);

      // Reuse already-mapped entity types from the base datastore (no classpath scanning per realm)
      for (Class<?> entityType : getMappedEntityTypesFromBase()) {
         mdatastore.getMapper().map(entityType);
      }

      // Apply indexes and document validations
      if (baseConfig.applyIndexes()) {
         mdatastore.applyIndexes();
      }

      if (baseConfig.applyDocumentValidations()) {
         mdatastore.applyDocumentValidations();
      }

      return mdatastore;
   }

   /**
    * Returns the entity types already mapped on the base datastore, so realm datastores can register
    * the same types by class without repeating package scanning.
    */
   private List<Class<?>> getMappedEntityTypesFromBase() {
      if (cachedMappedEntityTypes == null) {
         synchronized (this) {
            if (cachedMappedEntityTypes == null) {
               List<Class<?>> types = new ArrayList<>();
               for (EntityModel em : dataStore.getMapper().getMappedEntities()) {
                  types.add(em.getType());
               }
               cachedMappedEntityTypes = types;
               Log.debugf("MorphiaDataStoreWrapper: cached %d entity types from base mapper (used for all realm datastores)", cachedMappedEntityTypes.size());
            }
         }
      }
      return cachedMappedEntityTypes;
   }

    /* Original as of March 26th 2025
    if (mdatastore == null) {
         dataStore.getMapper().addInterceptor(validationInterceptor);
         dataStore.getMapper().addInterceptor(permissionRuleInterceptor);
         dataStore.getMapper().addInterceptor(auditInterceptor);
         dataStore.getMapper().addInterceptor(referenceInterceptor);
         //dataStore.applyIndexes();
         datastoreMap.put(realm, dataStore);
         mdatastore = dataStore;
      }
        return mdatastore;
      */
       /*if (mdatastore == null) {
         Log.warn("DataStore Null:  --- > Connecting to realm:" + realm);
         MorphiaConfig c = MorphiaConfig.load();
         c = c.database(realm);
         datastore = (MorphiaDatastore) Morphia.createDatastore(mongoClient, c);

       //   MapperOptions mapperOptions = MapperOptions.builder()
       //           .propertyDiscovery(MapperOptions.PropertyDiscovery.METHODS)
       //           .build();
       // datastore = Morphia.createDatastore(mongoClient, realm, mapperOptions);
         Log.warn("Created Datastore, for realm:" + realm);
       //  datastore.getMapper().addInterceptor(permissionRuleInterceptor);

       if (datastore.getClass().isAssignableFrom(MorphiaDatastore.class) ){
            MorphiaDatastore morphiaDatastore = (MorphiaDatastore) datastore;
            morphiaDatastore.getMapper().addInterceptor(validationInterceptor);
            morphiaDatastore.getMapper().addInterceptor(auditInterceptor);
            morphiaDatastore.getMapper().addInterceptor(referenceInterceptor);
         }

      /*
        Set<Class> classes = classScanner.scanForEntityBeans("com.e2eq.framework.security.model.persistent.models.security");

         classes.addAll(classScanner.scanForEntityBeans("com.e2eq.framework.model.persistent.base"));


         datastore.getMapper().mapPackage( "com.e2eq.framework.security.model.persistent.models.security");
         datastore.getMapper().mapPackage( "com.e2eq.framework.security.model.persistent.morphia");
         datastore.getMapper().mapPackage( "com.e2eq.framework.model.persistent.base");
         datastore.getMapper().mapPackage( "com.e2eq.framework.model.persistent.migration.base");


          Log.info("Found " + classes.size() +" Entities");
          for (Class c : classes) {
              Log.info("Mapping:" + c.getSimpleName());
              datastore.getMapper().map(c);
          }
         datastore.ensureIndexes();
         datastoreMap.put(datastore.getDatabase().getName(), datastore);

      }
      else {
         if (Log.isDebugEnabled()) {
            Log.debug("Data Store already connected for realm:" + realm + " database name:" + datastore.getDatabase().getName());
         }
      } */

}
