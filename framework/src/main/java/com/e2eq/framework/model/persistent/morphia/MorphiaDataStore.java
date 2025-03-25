package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.morphia.interceptors.*;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.MorphiaDatastore;
import dev.morphia.config.MorphiaConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class MorphiaDataStore {
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



  // @ConfigProperty(name = "quarkus.mongodb.database") public String databaseName;

   protected Map<String, MorphiaDatastore> datastoreMap = new HashMap<>();

   protected static final boolean ENABLE_ONE_DB_PER_TENANT = false;

   public MorphiaDataStore() {
   }

   public Datastore getDefaultSystemDataStore() {
      return getDataStore(SecurityUtils.systemRealm);
   }

   public synchronized MorphiaDatastore getDataStore(@NotNull String realm) {

      if (realm == null || realm.isEmpty()) {
         throw new IllegalArgumentException("parameter realm needs to be non null and non-empty");
      }

      if (!ENABLE_ONE_DB_PER_TENANT) {
         // one realm per tenant implies we just use a single realm for all tenants
         realm = SecurityUtils.systemRealm;
      }


      MorphiaDatastore mdatastore = datastoreMap.get(realm);
      if (mdatastore == null) {
         dataStore.getMapper().addInterceptor(validationInterceptor);
         dataStore.getMapper().addInterceptor(permissionRuleInterceptor);
         dataStore.getMapper().addInterceptor(auditInterceptor);
         dataStore.getMapper().addInterceptor(referenceInterceptor);
         //dataStore.applyIndexes();
         datastoreMap.put(realm, dataStore);
         mdatastore = dataStore;
      }
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
      return mdatastore;
   }

}
