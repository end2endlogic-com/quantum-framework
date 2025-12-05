package com.e2eq.framework.model.persistent.migration.base;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import dev.morphia.Datastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.semver4j.Semver;



import java.util.*;
import java.util.ArrayList;


@ApplicationScoped
@Startup
public class MigrationService {

   @ConfigProperty(name = "quantum.database.version")
   protected String targetDatabaseVersion;

   @ConfigProperty(name = "quantum.database.scope")
   protected String databaseScope;
   @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
   protected String defaultRealm;
   @ConfigProperty(name = "quantum.realmConfig.testRealm")
   protected String testRealm;
   @ConfigProperty(name = "quantum.realmConfig.systemRealm")
   protected String systemRealm;

   @ConfigProperty(name = "quantum.database.migration.enabled")
   protected boolean enabled;

   @Inject
   DatabaseVersionRepo databaseVersionRepo;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   BeanManager beanManager;

   @Inject
   ChangeSetRecordRepo changesetRecordRepo;

   @Inject
   MongoClient mongoClient;

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   @PostConstruct
   public void ensureSystemRealmInitialized () {
      if (!enabled) {
         Log.warn("!!!! >>>> Database migration is disabled by configuration (quantum.database.migration.enabled=false)");
         return;
      } else {
         Log.info(">> Migration Service enabled");
      }

      // Drop all indexes to avoid conflicts when re-running migrations
      try {
         for (String dbName : List.of(systemRealm, defaultRealm, testRealm)) {
            if (mongoClient.listDatabaseNames().into(new ArrayList<>()).contains(dbName)) {
               mongoClient.getDatabase(dbName).listCollectionNames().forEach(collName -> {
                  try {
                     mongoClient.getDatabase(dbName).getCollection(collName).dropIndexes();
                  } catch (Exception e) {
                     Log.debugf("Could not drop indexes on %s.%s: %s", dbName, collName, e.getMessage());
                  }
               });
            }
         }
      } catch (Exception e) {
         Log.warnf("Error dropping indexes: %s", e.getMessage());
      }

      Log.infof(">> ### Checking if system realm %s is initialized ###", systemRealm);
      // using the mongoClient check if the system realm exists
      // if not, create it
      // if it does, check if the database version is correct
      // if not, throw an exception
      // if it does, return
      List<String> databaseNames = mongoClient.listDatabaseNames().into(new ArrayList<>());
      if (!databaseNames.contains(systemRealm)) {
         Log.warnf("    ### System realm %s does not exist, creating it", systemRealm);
         Multi<String> multi = Multi.createFrom().emitter(emitter -> {
            SecurityCallScope.runWithContexts(securityUtils.getSystemPrincipalContext(),
               securityUtils.getSystemSecurityResourceContext(), () -> {
               runAllUnRunMigrations(systemRealm, emitter);
            });
         });
         multi.subscribe().with(
            item -> System.out.println(">: " + item),
            failure -> failure.printStackTrace(),
            () -> System.out.println(">: Done")
         );

      } else {
         Log.infof("###  System realm %s exists, checking if it is initialized", systemRealm);
         try {
            checkInitialized(systemRealm);
         } catch (DatabaseMigrationException e) {
            Log.warnf("    ### System realm %s is not initialized, initializing it", systemRealm);
            Multi.createFrom().emitter(emitter -> {
               runAllUnRunMigrations(systemRealm, emitter);
            });
         }
      }
   }


   public void checkInitialized (String realm) {
      Optional<DatabaseVersion> oDatabaseVersion = databaseVersionRepo.findCurrentVersion(realm);
      if (!oDatabaseVersion.isPresent()) {
         throw new DatabaseMigrationException(String.format("Database Version was not found, for realm: %s. Please initialize / run migrations on the database", realm));
      }
      DatabaseVersion currentVersion = oDatabaseVersion.get();
      Semver currentSemVersion = currentVersion.getCurrentSemVersion();
      Semver requiredSemVersion = Semver.parse(targetDatabaseVersion);
      if (currentSemVersion.compareTo(requiredSemVersion) < 0) {
         throw new DatabaseMigrationException(realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      }

      // Check for pending changesets with mismatched checksums
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
      List<ChangeSetBean> allChangeSets = getAllChangeSetBeans();
      for (ChangeSetBean chb : allChangeSets) {
         Optional<ChangeSetRecord> record = changesetRecordRepo.findLatestByChangeSetName(datastore, chb.getName());
         if (record.isPresent() && chb.getChecksum() != null && !chb.getChecksum().equals(record.get().getChecksum())) {
            throw new DatabaseMigrationException(String.format("Database %s has changeset %s with mismatched checksum. Please run migrations.", realm, chb.getName()));
         }
      }

      if (currentSemVersion.compareTo(requiredSemVersion) > 0) {
         Log.warnf("Database %s version is higher than required. Current version: %s, required version: %s", realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      } else {
         Log.infof("Database %s version is up to date. Current version: %s, required version: %s and all checksums match", realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      }
   }

   public void dropIndexOnCollection (String realm, String collectionName) {
      mongoClient.getDatabase(realm).getCollection(collectionName).dropIndexes();
   }

   public void applyIndexes (String realmId) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      morphiaDataStoreWrapper.getDataStore(realmId).applyIndexes();
   }

   public void applyIndexes (String realmId, String collection) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      Optional<EntityModel> em = morphiaDataStoreWrapper.getDataStore(realmId).getMapper().getMappedEntities().stream().filter(entity -> entity.collectionName().equals(collection)).findFirst();
      if (!em.isPresent()) {
         throw new NotFoundException(String.format("Collection %s not found in realm %s", collection, realmId));
      }
      morphiaDataStoreWrapper.getDataStore(realmId).ensureIndexes(em.get().getType());
   }

   public void dropAllIndexes (String realmId) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      morphiaDataStoreWrapper.getDataStore(realmId).getMapper().getMappedEntities().forEach(entity -> {
         mongoClient.getDatabase(realmId).getCollection(entity.collectionName()).dropIndexes();
      });
   }


   public void checkMigrationRequired () {
      checkInitialized(defaultRealm);
      checkInitialized(systemRealm);
      checkInitialized(testRealm);
   }

   protected void log (String message, MultiEmitter<? super String> emitter) {
      Log.info(message);
      emitter.emit(message);
   }


   public void checkDataBaseVersion () {
      Log.info("MigrationService check is enabled");
      Log.infof("MigrationService targetDatabaseVersion: %s", targetDatabaseVersion);
      Log.infof("MigrationService databaseScope: %s", databaseScope);
      DistributedLock lock = getMigrationLock(systemRealm);
      lock.acquire();
      try {
         migrationRequired(morphiaDataStoreWrapper.getDataStore(defaultRealm), targetDatabaseVersion);
         migrationRequired(morphiaDataStoreWrapper.getDataStore(systemRealm), targetDatabaseVersion);
         migrationRequired(morphiaDataStoreWrapper.getDataStore(testRealm), targetDatabaseVersion);
      } finally {
         lock.release();
      }
   }

   protected DistributedLock getMigrationLock (String realm) {
      MongoCollection<Document> collection = mongoClient
                                                .getDatabase("sherlock")
                                                .getCollection("locks");
      Sherlock sherlock = MongoSherlock.create(collection);
      // create Lock name

      DistributedLock lock = sherlock.createLock(String.format("migration-lock-%s", realm));
      return lock;
   }

   public Optional<DatabaseVersion> getCurrentDatabaseVersion (String realm) {
      return getCurrentDatabaseVersion(morphiaDataStoreWrapper.getDataStore(realm));
   }

   public Optional<DatabaseVersion> getCurrentDatabaseVersion (Datastore datastore) {
      // Find the current version of the database.
      return databaseVersionRepo.findCurrentVersion(datastore);
   }

   public void migrationRequired (Datastore datastore, String requiredVersion) {

      Semver requiredSemver = Semver.parse(requiredVersion);
      if (requiredSemver == null) {
         throw new IllegalArgumentException(String.format(" realm: %s ,The current version string: %s is not parsable, check semver4j for more details about string format", datastore.getDatabase().getName(), requiredVersion));
      }

      Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findCurrentVersion(datastore);
      if (odbVersion.isPresent()) {
         Log.infof("DBVersion: %s", odbVersion.get().toString());
         if (odbVersion.get().getCurrentSemVersion().isLowerThan(requiredVersion)) {
            throw new DatabaseMigrationException(datastore.getDatabase().getName(), odbVersion.get().toString(), requiredVersion);
         }

      } else
         throw new IllegalStateException(String.format("Empty database version collection found for  dataStore:%s, dataStore needs to be initialized", datastore.getDatabase().getName()));
   }

   public DatabaseVersion saveDatabaseVersion (Datastore datastore, String versionString) {
      if (versionString == null || versionString.isEmpty()) {
         throw new IllegalArgumentException("versionString cannot be null or empty");
      }


      Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(datastore, datastore.getDatabase().getName());
      DatabaseVersion dbVersion;

      if (odbVersion.isPresent()) {
         dbVersion = odbVersion.get();
         Log.infof("DBVersion: %s", dbVersion.toString());
         dbVersion.setCurrentVersionString(versionString);
         dbVersion.setLastUpdated(new java.util.Date());
      } else {
         dbVersion = new DatabaseVersion();
         dbVersion.setCurrentVersionString(versionString);
         dbVersion.setLastUpdated(new java.util.Date());
         dbVersion.setRefName(datastore.getDatabase().getName());
      }

      return databaseVersionRepo.save(datastore, dbVersion);
   }

   public List<ChangeSetBean> getAllChangeSetBeans () {
      if (Log.isDebugEnabled())
       Log.debug("== Finding changeSetBeans ===============");
      else {
         Log.info("Scanning for changeSet Beans - should only be seen on startup");
      }
      List<ChangeSetBean> changeSetBeans = new ArrayList<>();
      Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
      for (Bean bean : changeSets) {
         CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
         ChangeSetBean chb = (ChangeSetBean) beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
         if (Log.isDebugEnabled()) {}
            Log.debugf("Found ChangeSetBean: %s", chb.getName());
         changeSetBeans.add(chb);
      }

      // sort the change set beans in order of from / to and priority
      changeSetBeans.sort(Comparator.comparing(ChangeSetBean::getDbToVersionInt)
                             .thenComparing(ChangeSetBean::getPriority));

      return changeSetBeans;
   }

   public List<ChangeSetBean> getAllPendingChangeSetBeans (String realm, MultiEmitter<? super String> emitter) {
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
      Optional<DatabaseVersion> oCurrentDbVersion = this.getCurrentDatabaseVersion(datastore);
      DatabaseVersion currentDbVersion;
      if (!oCurrentDbVersion.isPresent()) {
         log(String.format("No database version found in the database for realm: %s, assuming 1.0.0", realm), emitter);
         currentDbVersion = new DatabaseVersion();
         currentDbVersion.setCurrentVersionString("1.0.0");
         currentDbVersion = getDatabaseVersionRepo().save(realm, currentDbVersion);
      } else {
         currentDbVersion = oCurrentDbVersion.get();
         log(String.format("Current database version: %s", currentDbVersion.getCurrentVersionString()), emitter);
      }


      Semver currentSemDatabaseVersion = currentDbVersion.getCurrentSemVersion();
      List<ChangeSetBean> changeSets = getAllChangeSetBeans();
      log(String.format("Found %d Change Sets:", changeSets.size()), emitter);
      if (changeSets.isEmpty()) {
         log("No Change Sets found", emitter);
      }
      log(String.format("Found %d Change Sets:", changeSets.size()), emitter);
      changeSets.forEach(changeSetBean -> {
         log(String.format("    Change Set: %s", changeSetBean.getName()), emitter);
      });
      List<ChangeSetBean> pendingChangeSetBeans = new ArrayList<>();

      for (ChangeSetBean chb : changeSets) {
         Semver toVersion = new Semver(chb.getDbToVersion());
         Semver currentVersion = new Semver(currentSemDatabaseVersion.getVersion());

         Optional<ChangeSetRecord> record = changesetRecordRepo.findLatestByChangeSetName(datastore, chb.getName());
         boolean shouldRun = !record.isPresent() ||
                             record.get().getChangeSetVersion() < chb.getChangeSetVersion() ||
                             (chb.getChecksum() != null && !chb.getChecksum().equals(record.get().getChecksum()));

         if (toVersion.isGreaterThanOrEqualTo(currentVersion)) {
            log(String.format("ToVersion:%s, is greater than or equal to current version:%s", toVersion.getVersion(), currentVersion.getVersion()), emitter);
            if (shouldRun) {
               String reason = !record.isPresent() ? "no record" :
                              (record.get().getChangeSetVersion() < chb.getChangeSetVersion() ? "version changed" : "checksum changed");
               log(String.format(">> Adding Change Set: %s (v%d) in realm %s - reason: %s", chb.getName(), chb.getChangeSetVersion(), datastore.getDatabase().getName(), reason), emitter);
               pendingChangeSetBeans.add(chb);
            } else {
               log(String.format(">> Already executed change set: %s (latest applied v%d) in realm %s on %tc ", chb.getName(), record.get().getChangeSetVersion(), datastore.getDatabase().getName(), record.get().getLastExecutedDate()), emitter);
            }
         } else if (shouldRun) {
            log(String.format(">> Adding Change Set: %s (v%d) in realm %s - reason: checksum changed (older version) or change record not found", chb.getName(), chb.getChangeSetVersion(), datastore.getDatabase().getName()), emitter);
            pendingChangeSetBeans.add(chb);
         } else {
            log(String.format(">> Skipping Change Set:%s (already applied, toVersion:%s < currentVersion:%s)", chb.getName(), toVersion.getVersion(), currentSemDatabaseVersion), emitter);
         }
      }
      return pendingChangeSetBeans;
   }

   public DatabaseVersionRepo getDatabaseVersionRepo () {
      return databaseVersionRepo;
   }


   public void updateChangeLog (MorphiaSession ds, String realm, ChangeSetBean changeSetBean, MultiEmitter<? super String> emitter) {
      // Upsert ChangeSetRecord by changeSetName to avoid duplicate-key on older deployments with a unique index on name only
      Optional<ChangeSetRecord> existing = changesetRecordRepo.findLatestByChangeSetName(ds, changeSetBean.getName());
      ChangeSetRecord record;
      if (existing.isPresent()) {
         record = existing.get();
      } else {
         record = new ChangeSetRecord();
      }
      // Populate/overwrite all fields
      record.setRealm(realm);
      record.setRefName(changeSetBean.getName());
      record.setAuthor(changeSetBean.getAuthor());
      record.setChangeSetName(changeSetBean.getName());
      record.setDescription(changeSetBean.getDescription());
      record.setPriority(changeSetBean.getPriority());
      record.setDbFromVersion(changeSetBean.getDbFromVersion());
      record.setDbFromVersionInt(changeSetBean.getDbFromVersionInt());
      record.setDbToVersion(changeSetBean.getDbToVersion());
      record.setDbToVersionInt(changeSetBean.getDbToVersionInt());
      record.setChangeSetVersion(changeSetBean.getChangeSetVersion());
      record.setChecksum(changeSetBean.getChecksum());
      record.setLastExecutedDate(new Date());
      record.setScope(changeSetBean.getScope());
      record.setSuccessful(true);

      changesetRecordRepo.save(ds, record);

      DatabaseVersion databaseVersion;
      Optional<DatabaseVersion> oversion = databaseVersionRepo.findCurrentVersion(ds);
      if (!oversion.isPresent()) {
         Log.warnf("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm);
         emitter.emit(String.format("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm));
         databaseVersion = new DatabaseVersion();
         databaseVersion.setRefName(realm);
         databaseVersion.setCurrentVersionString(record.getDbToVersion());
         databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
      } else {
         databaseVersion = oversion.get();
         if (databaseVersion.getCurrentSemVersion().isLowerThan(record.getDbToVersion())) {
            databaseVersion.setCurrentVersionString(record.getDbToVersion());
            databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
         }
      }
   }

   public void runChangeSetBean(String changeSetName, String realm, MultiEmitter<? super String> emitter) throws Exception {

      List<ChangeSetBean> changeSets = getAllChangeSetBeans();
      Optional<ChangeSetBean> changeSetBeanOptional = changeSets.stream().filter(changeSetBean -> changeSetBean.getName().equals(changeSetName)).findFirst();
      if (!changeSetBeanOptional.isPresent()) {
         throw new NotFoundException(String.format("Change Set Bean:%s not found", changeSetName));
      }
      ChangeSetBean changeSetBean = changeSetBeanOptional.get();

      // get a lock first:
      DistributedLock lock = getMigrationLock(realm);
      log(String.format("-- Got Migration Lock Executing change sets on database / realm:%s --", realm), emitter);
      lock.runLocked(() -> {
         MorphiaSession ds = morphiaDataStoreWrapper.getDataStore(realm).startSession();
         log(String.format("        Executing Change Set: %s in realm %s", changeSetBean.getName(), realm), emitter);
         emitter.emit(String.format("        Executing Change Set: %s in realm %s", changeSetBean.getName(), realm));
         try {
            changeSetBean.execute(ds, mongoClient, emitter);
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
         log(String.format("        Executed Change Set: %s in realm %s", changeSetBean.getName(), realm), emitter);
         emitter.emit(String.format("        Executed Change Set: %s in realm %s", changeSetBean.getName(), realm));
         updateChangeLog(ds, realm, changeSetBean, emitter);
      });
   }

   public void runAllUnRunMigrations (String realm, MultiEmitter<? super String> emitter) {

      Log.info("-- Running all migrations for realm: " + realm);
      List<ChangeSetBean> changeSetList = getAllPendingChangeSetBeans(realm, emitter);
      if (!changeSetList.isEmpty()) {
         Log.infof("-------------- Migration Starting for: %s--------------", realm);
         emitter.emit(String.format("-------------- Migration Starting for: %s--------------", realm));
         // for each now execute the change set bean
         Log.infof("-- Executing %d change sets --", changeSetList.size());
         emitter.emit(String.format("-- Executing %d change sets --", changeSetList.size()));

         // get a lock first:
         DistributedLock lock = getMigrationLock(realm);
         log(String.format("-- Got Migration Lock Executing change sets on database / realm:%s --", realm), emitter);
         lock.runLocked(() -> {
            changeSetList.forEach(changeSetBean -> {
               log(String.format("    checking for previous execution of Change Set: %s, in database %s", changeSetBean.getName(), realm ), emitter);
               // first check if this change set has run already or not
               Optional<ChangeSetRecord> changeSetRec = changesetRecordRepo.findLatestByChangeSetName(morphiaDataStoreWrapper.getDataStore(realm), changeSetBean.getName());
               boolean shouldRun = !changeSetRec.isPresent() ||
                                   changeSetRec.get().getChangeSetVersion() < changeSetBean.getChangeSetVersion() ||
                                   (changeSetBean.getChecksum() != null && !changeSetBean.getChecksum().equals(changeSetRec.get().getChecksum()));
               if (changeSetRec.isPresent()) {
                  log(String.format("    Found existing record for %s: version=%d, checksum=%s; Bean: version=%d, checksum=%s",
                     changeSetBean.getName(), changeSetRec.get().getChangeSetVersion(), changeSetRec.get().getChecksum(),
                     changeSetBean.getChangeSetVersion(), changeSetBean.getChecksum()), emitter);
               }
               if (shouldRun) {

                  if ((changeSetBean.getApplicableDatabases() == null) ||
                         (changeSetBean.getApplicableDatabases() != null && changeSetBean.getApplicableDatabases().contains(realm))
                  ) {
                     Log.infof("Executing Change Set:%s", changeSetBean.getName());
                     emitter.emit(String.format("Executing Change Set:%s", changeSetBean.getName()));

                     if (changeSetBean.isOverrideDatabase() && changeSetBean.getOverrideDatabaseName() != null &&
                            !changeSetBean.getOverrideDatabaseName().isEmpty() && !changeSetBean.getOverrideDatabaseName().equalsIgnoreCase(realm)) {
                        log(String.format("Overridden Database for changeSetBean:%s, to database:%s from default:%s", changeSetBean.getName(), changeSetBean.getOverrideDatabaseName(), realm), emitter);
                        MorphiaSession ods = morphiaDataStoreWrapper.getDataStore(changeSetBean.getOverrideDatabaseName()).startSession();

                        DistributedLock olock = getMigrationLock(changeSetBean.getOverrideDatabaseName());
                        olock.runLocked(() -> {
                           try {
                              Log.infof("        Starting Transaction for Change Set:%s on database: %s", changeSetBean.getName(), changeSetBean.getOverrideDatabaseName());
                              emitter.emit(String.format("        Starting Transaction for Change Set:%s", changeSetBean.getName()));

                              ods.startTransaction();
                              changeSetBean.execute(ods, mongoClient, emitter);
                              updateChangeLog(ods, changeSetBean.getOverrideDatabaseName(), changeSetBean, emitter);
                              ods.commitTransaction();
                              Log.infof("        Committed Transaction for Change Set:%s", changeSetBean.getName());
                              emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));
                              ods.commitTransaction();
                              ods.close();
                           } catch (Throwable e) {
                              emitter.fail(e);
                              e.printStackTrace();
                              ods.abortTransaction();
                              ods.close();
                              throw new RuntimeException(e);
                           }
                        });
                     } else {
                        MorphiaSession ds = morphiaDataStoreWrapper.getDataStore(realm).startSession();
                        try {
                           log(String.format("        Starting Transaction for Change Set:%s on database", changeSetBean.getName(), realm), emitter);

                           ds.startTransaction();
                           changeSetBean.execute(ds, mongoClient, emitter);
                           updateChangeLog(ds, realm, changeSetBean, emitter);
                           ds.commitTransaction();
                           Log.infof("        Committed Transaction for Change Set:%s", changeSetBean.getName());
                           emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));
                        } catch (Throwable e) {
                           emitter.fail(e);
                           e.printStackTrace();
                           ds.abortTransaction();
                           ds.close();
                           throw new RuntimeException(e);
                        }
                     }
                  } else {
                     log(String.format("Ignoring Change Set:%s because it is not applicable to realm:%s", changeSetBean.getName(), realm), emitter);
                  }
               } else {
                  Log.infof("Change Set:%s has already been executed on database %s", changeSetBean.getName(), realm);
               }
               });
               log(String.format("-- Lock Released --"), emitter);
               log("-- All Change Sets executed --", emitter);
               log(String.format("-------------- Migration Completed for: %s--------------", realm), emitter);
            });
         } else {
            log(String.format("-- No pending change sets found for realm: %s", realm), emitter);
         }
   }



    private static @NotNull ChangeSetRecord newChangeSetRecord(String realm, ChangeSetBean changeSetBean) {
        ChangeSetRecord record = new ChangeSetRecord();
        record.setRealm(realm);
        record.setRefName(changeSetBean.getName());
        record.setAuthor(changeSetBean.getAuthor());
        record.setChangeSetName(changeSetBean.getName());
        record.setDescription(changeSetBean.getDescription());
        record.setPriority(changeSetBean.getPriority());
        record.setDbFromVersion(changeSetBean.getDbFromVersion());
        record.setDbFromVersionInt(changeSetBean.getDbFromVersionInt());
        record.setDbToVersion(changeSetBean.getDbToVersion());
        record.setDbToVersionInt(changeSetBean.getDbToVersionInt());
        record.setChangeSetVersion(changeSetBean.getChangeSetVersion());
        record.setLastExecutedDate(new Date());
        record.setScope(changeSetBean.getScope());
        record.setSuccessful(true);
        return record;
    }

}
