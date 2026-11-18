package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.morphia.interceptors.*;

import com.e2eq.framework.util.EnvConfigUtils;
import com.coditory.sherlock.connector.AcquireResult;
import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.Duration;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import dev.morphia.config.MorphiaConfig;
import dev.morphia.mapping.codec.pojo.EntityModel;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

   /**
    * Compatibility escape hatch only. The default keeps global identity/security
    * resources out of tenant realm datastores; embedded auth still maps those
    * resources in the configured system realm.
    */
   @ConfigProperty(name = "quantum.morphia.map-global-resources-to-tenant-realms", defaultValue = "false")
   boolean mapGlobalResourcesToTenantRealms;

   @ConfigProperty(
       name = "quantum.morphia.tenant-excluded-entity-package-prefixes",
       defaultValue = RealmEntityMappingPolicy.DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES
   )
   String tenantExcludedEntityPackagePrefixes;

   @ConfigProperty(name = "quantum.morphia.tenant-excluded-entity-classes")
   Optional<String> tenantExcludedEntityClasses;

   @ConfigProperty(name = "quantum.mode", defaultValue = "embedded")
   String quantumMode;



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

      MorphiaDatastore open = datastoreMap.get(realm);
      if (open != null) {
         return open;
      }
      requireAdmitted(realm);
      return datastoreMap.computeIfAbsent(realm, r -> createMorphiaDatastore(r));
   }

   /**
    * Realms a datastore may be opened for although they are neither registered nor existing:
    * a regular expression (default none), for test realms only. Production leaves it unset.
    */
   @ConfigProperty(name = "quantum.morphia.unregistered-realm-pattern")
   Optional<String> unregisteredRealmPattern = Optional.empty();

   @Inject
   jakarta.enterprise.inject.Instance<RealmDatastoreAdmission> realmAdmission;

   private final java.util.Set<String> existingDatabases = ConcurrentHashMap.newKeySet();

   private static final ThreadLocal<java.util.Set<String>> PROVISIONING = ThreadLocal.withInitial(java.util.HashSet::new);

   /**
    * Opens a scope in which {@code realm}'s datastore may be created before the realm is
    * registered: realm registration and provisioning, which create the realm, use it. Close it
    * when done.
    */
   public static AutoCloseable provisioning(String realm) {
      java.util.Set<String> realms = PROVISIONING.get();
      boolean added = realms.add(realm);
      return () -> {
         if (added) {
            realms.remove(realm);
         }
      };
   }

   /**
    * A datastore opens (and its indexes are built, which creates the database) only for a realm
    * that exists or is registered: a configured realm (system, default, test), a realm whose
    * database exists, a realm the
    * catalog registers (this service's own system realm catalog, then {@link RealmDatastoreAdmission}),
    * a realm being provisioned ({@link #provisioning}), or a test realm matching
    * {@code quantum.morphia.unregistered-realm-pattern}. Anything else, such as a requested realm
    * name no one registered, is refused with {@link UnregisteredRealmException}, so a caller-supplied
    * name can never create a database.
    */
   void requireAdmitted(String realm) {
      if (configuredRealm(realm) || PROVISIONING.get().contains(realm)
              || unregisteredRealmPattern.filter(p -> !p.isBlank()).map(p -> realm.matches(p)).orElse(false)
              || databaseExists(realm) || registeredLocally(realm) || registeredInCatalog(realm)) {
         return;
      }
      Log.warnf("Refused a datastore for realm '%s': it is not registered and has no database", realm);
      throw new UnregisteredRealmException(realm);
   }

   /** The realms this deployment configures (system, default, test): deployment settings, not caller input. */
   private boolean configuredRealm(String realm) {
      return realm.equals(envConfigUtils.getSystemRealm()) || realm.equals(envConfigUtils.getDefaultRealm())
              || realm.equals(envConfigUtils.getTestRealm());
   }

   private boolean databaseExists(String realm) {
      if (existingDatabases.contains(realm)) {
         return true;
      }
      for (String name : mongoClient.listDatabaseNames()) {
         existingDatabases.add(name);
      }
      return existingDatabases.contains(realm);
   }

   private boolean registeredLocally(String realm) {
      String collection = collectionNameOf(com.e2eq.framework.model.security.Realm.class);
      return mongoClient.getDatabase(envConfigUtils.getSystemRealm()).getCollection(collection)
              .find(com.mongodb.client.model.Filters.eq("refName", realm)).limit(1).first() != null;
   }

   private boolean registeredInCatalog(String realm) {
      if (realmAdmission == null || !realmAdmission.isResolvable()) {
         return false;
      }
      return realmAdmission.get().registered(realm);
   }

   /** Shared database used only for short-lived coordination locks; mirrors MigrationService. */
   @ConfigProperty(name = "quantum.database.coordination-database", defaultValue = "quantum-coordination-shared")
   String coordinationDatabase;

   /**
    * How long the index lock is held before it expires.
    *
    * <p>An expiry is required, not optional: a process killed mid-build would
    * otherwise hold the lock forever and no realm could ever create indexes again.
    * It must comfortably exceed a cold index build for the largest realm.</p>
    */
   @ConfigProperty(name = "quantum.database.index-lock-duration-seconds", defaultValue = "300")
   long indexLockDurationSeconds;

   private DistributedLock indexLock(String realm) {
      MongoCollection<Document> collection = mongoClient
              .getDatabase(coordinationDatabase)
              .getCollection("locks");
      Sherlock sherlock = MongoSherlock.create(collection);
      return sherlock.createLock(String.format("index-lock-%s", realm));
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

      // Reuse already-mapped entity types from the base datastore (no classpath scanning per realm),
      // but do not project system-plane security/identity entities into tenant realms.
      int mappedEntityCount = 0;
      int skippedEntityCount = 0;
      for (Class<?> entityType : getMappedEntityTypesFromBase()) {
         if (shouldMapEntityType(realm, entityType)) {
            mdatastore.getMapper().map(entityType);
            mappedEntityCount++;
         } else {
            skippedEntityCount++;
            Log.debugf("Skipping system-plane entity type %s for tenant realm/database %s", entityType.getName(), realm);
         }
      }
      if (skippedEntityCount > 0) {
         Log.infof(
             "MorphiaDataStoreWrapper: mapped %d entity types and skipped %d system-plane entity types for realm/database %s",
             mappedEntityCount,
             skippedEntityCount,
             realm
         );
      }

      // Apply indexes and document validations under a per-realm coordination lock.
      //
      // The first process to touch a realm builds its indexes. When two do it at
      // once -- an application provisioning a new realm while the control plane
      // opens a datastore for that same realm to project its status -- MongoDB is
      // handed two identical createIndexes for one collection and the second build
      // parks in "draining writes received during build" forever. It survives
      // killOp; only dropIndexes clears it. Every later index build on that
      // collection then queues behind it, so an install that reported completed
      // hangs for minutes on its next write. Observed twice, on two different
      // realms, both on `policy`.
      //
      // Serializing on the same coordination database the migration lock uses
      // makes index creation exclusive per realm. The lock name is deliberately
      // NOT the migration lock's: migration holds that one while opening
      // datastores, and these locks are not reentrant.
      if (baseConfig.applyIndexes() || baseConfig.applyDocumentValidations()) {
         DistributedLock lock = indexLock(realm);
         AcquireResult result = lock.runLocked(Duration.ofSeconds(indexLockDurationSeconds), () -> {
            if (baseConfig.applyIndexes()) {
               mdatastore.applyIndexes();
            }
            if (baseConfig.applyDocumentValidations()) {
               mdatastore.applyDocumentValidations();
            }
         });
         if (!result.acquired()) {
            // Another process holds the lock and is building the very same indexes.
            // Skipping is the point: running anyway is exactly the concurrent build
            // this exists to prevent, and the holder creates what this realm needs.
            Log.warnf("Index creation for realm/database %s skipped: another process holds "
                    + "the index lock and is building the same indexes", realm);
         }
      }

      return mdatastore;
   }

   /**
    * Returns the entity types already mapped on the base datastore, so realm datastores can register
    * the same types by class without repeating package scanning.
    */
   /**
    * The collection {@code type} is stored in, from the base mapper (mapping it there if needed). Works for
    * system-plane types that a tenant realm's datastore deliberately leaves unmapped.
    */
   public String collectionNameOf(Class<?> type) {
      return dataStore.getMapper().getEntityModel(type).collectionName();
   }

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

   private boolean shouldMapEntityType(String realm, Class<?> entityType) {
      return RealmEntityMappingPolicy.shouldMapToRealm(
          realm,
          envConfigUtils.getSystemRealm(),
          entityType,
          mapGlobalResourcesToTenantRealms,
          "remote".equalsIgnoreCase(quantumMode),
          tenantExcludedEntityPackagePrefixes,
          tenantExcludedEntityClasses.orElse("")
      );
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
