package com.e2eq.framework.model.persistent.migration.base;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.rest.exceptions.DatabaseMigrationException;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import dev.morphia.Datastore;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import lombok.Data;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.semver4j.Semver;



import java.util.*;
import java.util.ArrayList;


@ApplicationScoped
@Data
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

    @ConfigProperty(name= "quantum.database.migration.changeset.package")
    protected String changeSetPackage;

    @ConfigProperty(name= "quantum.database.migration.enabled")
    protected boolean enabled;

    @Inject
    DatabaseVersionRepo databaseVersionRepo;

    @Inject
    BeanManager beanManager;

    @Inject
    ChangeSetRecordRepo changesetRecordRepo;

    @Inject
    MongoClient mongoClient;

    @Inject
    MorphiaDataStore morphiaDataStore;

    @Inject
    SecurityUtils securityUtils;




    public  void checkInitialized(String realm) {
       if (!databaseVersionRepo.findCurrentVersion(realm).isPresent() ) {
           throw new DatabaseMigrationException(String.format("Database %s not initialized. Please initialize / run migrations on the database", realm));
       }
    }

    public void isMigrationRequired() {
      checkInitialized(defaultRealm);
      checkInitialized(systemRealm);
      checkInitialized(testRealm);
    }


    public void checkDataBaseVersion() {
        Log.info("MigrationService check is enabled");
        Log.infof("MigrationService targetDatabaseVersion: %s", targetDatabaseVersion);
        Log.infof("MigrationService databaseScope: %s", databaseScope);
        DistributedLock lock = getMigrationLock(systemRealm);
        lock.acquire();
        try {
            migrationRequired(morphiaDataStore.getDataStore(defaultRealm),  targetDatabaseVersion);
            migrationRequired(morphiaDataStore.getDataStore(systemRealm),  targetDatabaseVersion);
            migrationRequired(morphiaDataStore.getDataStore(testRealm), targetDatabaseVersion);
        } finally {
            lock.release();
        }
    }

    protected DistributedLock getMigrationLock(String realm) {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");
        Sherlock sherlock = MongoSherlock.create(collection);
        // create Lock name

        DistributedLock lock = sherlock.createLock(String.format("migration-lock-%s", realm));
        return lock;
    }

    public Optional<DatabaseVersion> getCurrentDatabaseVersion( String realm) {
        return getCurrentDatabaseVersion(morphiaDataStore.getDataStore(realm));
    }

    public Optional<DatabaseVersion> getCurrentDatabaseVersion(Datastore datastore) {
        // Find the current version of the database.
        return databaseVersionRepo.findCurrentVersion(datastore);
    }

    public void migrationRequired(Datastore datastore,  String requiredVersion) {

        Semver requiredSemver = Semver.parse(requiredVersion);
        if (requiredSemver == null) {
            throw new IllegalArgumentException(String.format(" realm: %s ,The current version string: %s is not parsable, check semver4j for more details about string format", datastore.getDatabase().getName(), requiredVersion));
        }

        Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findCurrentVersion(datastore);
        if (odbVersion.isPresent()) {
            Log.infof("DBVersion: %s", odbVersion.get().toString());
             if (odbVersion.get().getCurrentSemVersion().isLowerThan(requiredVersion)) {
                 throw new DatabaseMigrationException( datastore.getDatabase().getName(), odbVersion.get().toString(), requiredVersion);
             }

        } else
            throw new IllegalStateException(String.format("Empty database version collection found for  dataStore:%s, dataStore needs to be initialized",  datastore.getDatabase().getName()));
    }

    public DatabaseVersion saveDatabaseVersion(Datastore datastore,  String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            throw new IllegalArgumentException("versionString cannot be null or empty");
        }


        Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(datastore, datastore.getDatabase().getName());
        DatabaseVersion dbVersion;

        if (odbVersion.isPresent()) {
            dbVersion = odbVersion.get();
            Log.infof("DBVersion: %s",dbVersion.toString());
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

   public  List<ChangeSetBean> getAllChangeSetBeans() {
        List<ChangeSetBean> changeSetBeans = new ArrayList<>();
        Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
        for (Bean bean : changeSets) {
            CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
            ChangeSetBean chb = (ChangeSetBean) beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
            changeSetBeans.add(chb);
        }

        // sort the change set beans in order of from / to and priority
        changeSetBeans.sort(Comparator.comparing(ChangeSetBean::getDbToVersionInt)
                .thenComparing(ChangeSetBean::getPriority));

        return changeSetBeans;
    }

   public List<ChangeSetBean> getAllPendingChangeSetBeans(String realm, MultiEmitter<? super String> emitter ) {
       Datastore datastore = morphiaDataStore.getDataStore(realm);
       Optional<DatabaseVersion> oCurrentDbVersion = this.getCurrentDatabaseVersion(datastore);
       DatabaseVersion currentDbVersion;
       if (!oCurrentDbVersion.isPresent()) {
           emitter.emit(String.format( "No database version found in the database for realm: %s, assuming 1.0.0",realm));
           currentDbVersion = new DatabaseVersion();
           currentDbVersion.setCurrentVersionString("1.0.0");
           currentDbVersion = getDatabaseVersionRepo().save(realm, currentDbVersion);
       } else {
           currentDbVersion = oCurrentDbVersion.get();
           emitter.emit(String.format("Current database version: %s", currentDbVersion.getCurrentVersionString()));
       }


        Semver currentSemDatabaseVersion = currentDbVersion.getCurrentSemVersion();
        List<ChangeSetBean> changeSets = getAllChangeSetBeans();
        emitter.emit(String.format("Found %d Change Sets:", changeSets.size()));
        changeSets.forEach(changeSetBean -> {
            emitter.emit(String.format("    %s", changeSetBean.getName()));
        });
        List<ChangeSetBean> pendingChangeSetBeans = new ArrayList<>();

        for (ChangeSetBean chb : changeSets) {
            Semver toVersion =new Semver(chb.getDbToVersion());
            Semver currentVersion = new Semver(currentSemDatabaseVersion.getVersion());
            if (toVersion.isGreaterThanOrEqualTo(currentVersion)) {
                Optional<ChangeSetRecord> record = changesetRecordRepo.findByRefName(datastore,chb.getName());
                if (!record.isPresent()) {
                    emitter.emit(String.format(">> Executing Change Set: %s in realm %s", chb.getName(), datastore.getDatabase().getName()));
                    pendingChangeSetBeans.add(chb);
                } else {
                    emitter.emit(String.format( ">> All ready executed change set: %s in realm %s on %tc ", chb.getName(), datastore.getDatabase().getName(), record.get().getLastExecutedDate()));
                }
            } else {
                emitter.emit(">> Ignoring Change Set:" + chb.getName() + " because it is not for the current database version:" + currentSemDatabaseVersion);
            }
        }
       return pendingChangeSetBeans;
    }

    public DatabaseVersionRepo getDatabaseVersionRepo() {
        return databaseVersionRepo;
    }

    public void runAllUnRunMigrations(String realm, MultiEmitter<? super String> emitter) {
        emitter.emit(String.format( "-------------- Migration Starting for: %s--------------", realm));

        List<ChangeSetBean> changeSetList = getAllPendingChangeSetBeans(realm, emitter );

        // for each now execute the change set bean
        emitter.emit(String.format("-- Executing %d change sets --", changeSetList.size()));
        // get a lock first:
        DistributedLock lock = getMigrationLock(realm);
        emitter.emit(String.format("-- Got Lock --"));
        lock.runLocked(() -> {
            changeSetList.forEach(changeSetBean -> {
                emitter.emit(String.format("Executing Change Set:%s", changeSetBean.getName()));
                MorphiaSession ds = morphiaDataStore.getDataStore(realm).startSession();

                try {
                    emitter.emit(String.format("        Starting Transaction for Change Set:%s", changeSetBean.getName()));
                    ds.startTransaction();

                    changeSetBean.execute(ds,mongoClient, realm);
                    ChangeSetRecord record = newChangeSetRecord(realm, changeSetBean);
                    changesetRecordRepo.save(ds, record);
                    DatabaseVersion databaseVersion;
                    Optional<DatabaseVersion> oversion = databaseVersionRepo.findCurrentVersion(ds);
                    if (!oversion.isPresent()) {
                        emitter.emit(String.format("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm));
                        databaseVersion = new DatabaseVersion();
                        databaseVersion.setRefName(realm);
                        databaseVersion.setCurrentVersionString(record.dbToVersion);
                        //databaseVersion.setCurrentSemVersion(new Semver(record.dbToVersion));
                        //databaseVersion.setCurrentVersionInt(record.dbToVersionInt);
                        databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
                    } else {
                        databaseVersion = oversion.get();
                        databaseVersion.setCurrentVersionString(record.dbToVersion);
                       // databaseVersion.setCurrentSemVersion(new Semver(record.dbToVersion));
                       // databaseVersion.setCurrentVersionInt(record.dbToVersionInt);
                        databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
                    }

                    ds.commitTransaction();
                    emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));

                } catch (Throwable e) {
                    emitter.fail(e);
                    e.printStackTrace();
                    ds.abortTransaction();
                    throw new RuntimeException(e);
                }
            });
        });

        emitter.emit(String.format("-- Lock Released --"));
        emitter.emit(String.format("-- All Change Sets executed --"));
        emitter.emit(String.format("-------------- Migration Completed for %s--------------", realm));

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
        record.setLastExecutedDate(new Date());
        record.setScope(changeSetBean.getScope());
        record.setSuccessful(true);
        return record;
    }

}
