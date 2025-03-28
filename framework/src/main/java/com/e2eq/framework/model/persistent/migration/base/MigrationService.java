package com.e2eq.framework.model.persistent.migration.base;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import dev.morphia.Datastore;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.semver4j.Semver;


import java.util.*;
import java.util.ArrayList;

@Slf4j
@ApplicationScoped
public class MigrationService {

    @ConfigProperty(name = "quantum.database.version")
    protected String targetDatabaseVersion;

    @ConfigProperty(name = "quantum.database.scope")
    protected String databaseScope;

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

    protected DistributedLock getMigrationLock(String realm) {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");
        Sherlock sherlock = MongoSherlock.create(collection);
        // create Lock name

        DistributedLock lock = sherlock.createLock(String.format("migration-lock-%s", realm));
        return lock;
    }

    public Optional<DatabaseVersion> getCurrentDatabaseVersion(Datastore datastore, String realm) {
        // Find the current version of the database.
        return databaseVersionRepo.findCurrentVersion(datastore,realm);
    }

    public boolean migrationRequired(Datastore datastore, String realm, String requiredVersion) {

        Semver requiredSemver = Semver.parse(requiredVersion);
        if (requiredSemver == null) {
            throw new IllegalArgumentException(String.format(" the current version string: %s is not parsable, check semver4j for more details about string format", requiredVersion));
        }

        Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(datastore, realm);
        if (odbVersion.isPresent()) {
            boolean rc = odbVersion.get().getCurrentSemVersion().isLowerThan(requiredVersion);
            return rc;
        } else
            return true;
    }

    public DatabaseVersion saveDatabaseVersion(Datastore datastore, String realm, String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            throw new IllegalArgumentException("versionString cannot be null or empty");
        }
        if (realm == null || realm.isEmpty()) {
            throw new IllegalArgumentException("realm cannot be null or empty");
        }

        Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(datastore, realm);
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
            dbVersion.setRefName(realm);
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

   public List<ChangeSetBean> getAllPendingChangeSetBeans(String realm) {
        Datastore datastore = morphiaDataStore.getDataStore(realm);
       Optional<DatabaseVersion> oCurrentDbVersion = this.getCurrentDatabaseVersion(datastore, realm);
       DatabaseVersion currentDbVersion;
       if (!oCurrentDbVersion.isPresent()) {
           Log.infof("No database version found in the database for realm: %s, assuming 1.0.0",realm);
           currentDbVersion = new DatabaseVersion();
           currentDbVersion.setCurrentVersionString("1.0.0");
           currentDbVersion = getDatabaseVersionRepo().save(realm, currentDbVersion);
       } else {
           currentDbVersion = oCurrentDbVersion.get();
       }


        Semver currentSemDatabaseVersion = currentDbVersion.getCurrentSemVersion();
        List<ChangeSetBean> changeSets = getAllChangeSetBeans();
        List<ChangeSetBean> pendingChangeSetBeans = new ArrayList<>();

        for (ChangeSetBean chb : changeSets) {
            Semver toVersion =new Semver(chb.getDbToVersion());
            Semver currentVersion = new Semver(currentSemDatabaseVersion.getVersion());
            if (toVersion.isGreaterThanOrEqualTo(currentVersion)) {
                Optional<ChangeSetRecord> record = changesetRecordRepo.findByRefName(datastore,chb.getName());
                if (!record.isPresent()) {
                    pendingChangeSetBeans.add(chb);
                } else {
                    Log.infof(">> All ready executed change set: %s in realm %s on %tc ", chb.getName(), datastore.getDatabase().getName(), record.get().getLastExecutedDate());
                }
            } else {
                Log.warn(">> Ignoring Change Set:" + chb.getName() + " because it is not for the current database version:" + currentSemDatabaseVersion);
            }
        }
       return pendingChangeSetBeans;
    }

    public DatabaseVersionRepo getDatabaseVersionRepo() {
        return databaseVersionRepo;
    }

    public void runAllUnRunMigrations(String realm) {
        Log.infof("-------------- Migration Starting for: %s--------------", realm);
        Datastore datastore=morphiaDataStore.getDataStore(realm);

        List<ChangeSetBean> changeSetList = getAllPendingChangeSetBeans(realm );

        // for each now execute the change set bean
        Log.infof("-- Executing %d change sets --", changeSetList.size());
        // get a lock first:
        DistributedLock lock = getMigrationLock(realm);
        Log.info("-- Got Lock --");
        lock.runLocked(() -> {
            changeSetList.forEach(changeSetBean -> {
                Log.infof("Executing Change Set:%s", changeSetBean.getName());
                MorphiaSession ds = morphiaDataStore.getDataStore(realm).startSession();
                try {
                    Log.infof("        Starting Transaction for Change Set:%s", changeSetBean.getName());
                    ds.startTransaction();

                    changeSetBean.execute(ds,realm);
                    ChangeSetRecord record = newChangeSetRecord(realm, changeSetBean);
                    changesetRecordRepo.save(ds, record);

                    ds.commitTransaction();
                    Log.infof("        Commited Transaction for Change Set:%s", changeSetBean.getName());
                } catch (Throwable e) {
                    e.printStackTrace();
                    ds.abortTransaction();
                    throw new RuntimeException(e);
                }
            });
        });

        Log.info("-- Lock Released --");
        Log.info("-- All Change Sets executed --");
        Log.infof("-------------- Migration Completed for %s--------------", realm);

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
