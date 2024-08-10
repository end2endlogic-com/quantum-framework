package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.morphia.interceptors.AuditInterceptor;
import com.e2eq.framework.model.persistent.morphia.interceptors.PermissionRuleInterceptor;
import com.e2eq.framework.model.persistent.morphia.interceptors.ValidationInterceptor;
import com.e2eq.framework.util.ClassScanner;
import com.mongodb.client.MongoClient;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class MorphiaDataStore {
   @Inject
   MongoClient mongoClient;
   @Inject
   PermissionRuleInterceptor permissionRuleInterceptor;
   @Inject
   ValidationInterceptor validationInterceptor;
   @Inject
   AuditInterceptor auditInterceptor;

   @Inject
   ClassScanner classScanner;

  // @ConfigProperty(name = "quarkus.mongodb.database") public String databaseName;

   protected Map<String, Datastore> datastoreMap = new HashMap<>();

   protected static final boolean ENABLE_ONE_DB_PER_TENANT = false;

   public MorphiaDataStore() {
   }

   public Datastore getDataStore() {
      return getDataStore("b2bintegrator-com");
   }

   public synchronized Datastore getDataStore(@NotNull String realm) {

      if (realm == null || realm.isEmpty()) {
         throw new IllegalArgumentException("parameter realm needs to be non null and non-empty");
      }

      if (!ENABLE_ONE_DB_PER_TENANT) {
         // one realm per tenant implies we just use a single realm for all tenants
         realm = "b2bintegrator-com";
      }


      Datastore datastore = datastoreMap.get(realm);

      if (datastore == null) {
         Log.warn("DataStore Null:  --- > Connecting to realm:" + realm);
         datastore = Morphia.createDatastore(mongoClient, realm);

       //   MapperOptions mapperOptions = MapperOptions.builder()
       //           .propertyDiscovery(MapperOptions.PropertyDiscovery.METHODS)
       //           .build();
       // datastore = Morphia.createDatastore(mongoClient, realm, mapperOptions);
         Log.warn("Created Datastore, for realm:" + realm);
         datastore.getMapper().addInterceptor(permissionRuleInterceptor);
         datastore.getMapper().addInterceptor(validationInterceptor);
         datastore.getMapper().addInterceptor(auditInterceptor);


        Set<Class> classes = classScanner.scanForEntityBeans("com.e2eq.framework.security.model.persistent.models.security");

         classes.addAll(classScanner.scanForEntityBeans("com.e2eq.framework.model.persistent.base"));
         // classes.addAll(classScanner.scanForEntityBeans("com.e2eq.app.integration.tp.model"));

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
      }
      return datastore;
   }

}
