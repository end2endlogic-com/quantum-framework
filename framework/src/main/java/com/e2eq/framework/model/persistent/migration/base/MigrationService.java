package com.e2eq.framework.model.persistent.migration.base;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;

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
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.semver4j.Semver;


import java.util.*;

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

    protected DistributedLock getMigrationLock(String realm) {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");
        Sherlock sherlock = MongoSherlock.create(collection);
        // create Lock name

        DistributedLock lock = sherlock.createLock(String.format("migration-lock-%s", realm));
        return lock;
    }

    public Optional<DatabaseVersion> getDatabaseVersion(String realm) {
        // Find the current version of the database.
        return databaseVersionRepo.findVersion(realm);
    }

    public boolean migrationRequired(String realm, String requiredVersion) {

        Semver requiredSemver = Semver.parse(requiredVersion);
        if (requiredSemver == null) {
            throw new IllegalArgumentException(String.format(" the current version string: %s is not parsable, check semver4j for more details about string format", requiredVersion));
        }

        Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(realm);
        if (odbVersion.isPresent()) {
            boolean rc = odbVersion.get().getCurrentSemVersion().isLowerThan(requiredVersion);
            return rc;
        } else
            return true;
    }

    public DatabaseVersion saveDatabaseVersion(String realm, String versionString) {
        if (versionString == null || versionString.isEmpty()) {
            throw new IllegalArgumentException("versionString cannot be null or empty");
        }
        if (realm == null || realm.isEmpty()) {
            throw new IllegalArgumentException("realm cannot be null or empty");
        }

        Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(realm);
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

        return databaseVersionRepo.save(dbVersion);
    }

    public DatabaseVersionRepo getDatabaseVersionRepo() {
        return databaseVersionRepo;
    }

    public void runAllUnRunMigrations(String realm) {

        // get the current version of the database
        Optional<DatabaseVersion> oCurrentDbVersion = this.getDatabaseVersion(realm);
        DatabaseVersion currentDbVersion;
        if (!oCurrentDbVersion.isPresent()) {
            Log.infof("No database version found in the database for realm: %s, assuming 1.0.0",realm);
            currentDbVersion = new DatabaseVersion();
            currentDbVersion.setCurrentVersionString("1.0.0");
        } else {
            currentDbVersion = oCurrentDbVersion.get();
        }

        Semver currentSemDatabaseVersion = currentDbVersion.getCurrentSemVersion();

        // use the beanManager to find all ChangeSetBeans
        Log.info("-- Searching for any change set beans--");
        Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
        List<ChangeSetBean> changeSetList= new LinkedList<>();
        Set<ChangeSetBean> changeSetBeans = new HashSet<>();

        // loop over the change set beans
        for (Bean bean : changeSets) {
            CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
            ChangeSetBean chb = (ChangeSetBean) beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
            // if the dbFromVersion is greater than or equal to the current database version, then add it to the list of change sets to consider.
            Semver toVersion =new Semver(chb.getDbToVersion());
            Semver currentVersion = new Semver(currentSemDatabaseVersion.getVersion());
            if (toVersion.isGreaterThanOrEqualTo(currentVersion)) {
                changeSetBeans.add(chb);
            } else {
                Log.warn(">> Ignoring Change Set:" + chb.getName() + " because it is not for the current database version:" + currentSemDatabaseVersion);
            }
        }

        // sort the change set beans in order of from / to and priority
        changeSetList.addAll(changeSetBeans);
        changeSetList.sort(Comparator.comparing(ChangeSetBean::getDbToVersionInt)
                .thenComparing(ChangeSetBean::getPriority));

        changeSetList.forEach(changeSetBean -> {Log.infof("Found Bean:%s from: %s to: %s", changeSetBean.getName(), changeSetBean.getDbFromVersion(), changeSetBean.getDbToVersion());});

        // for each now execute the change set bean
        Log.info("-- Executing change sets --");
        // get a lock first:
        DistributedLock lock = getMigrationLock(realm);
        lock.runLocked(() -> {
            changeSetList.forEach(changeSetBean -> {
                Log.infof("Executing Change Set:%s", changeSetBean.getName());
                MorphiaSession ds = this.getDatabaseVersionRepo().startSession(realm);
                try {
                    ds.startTransaction();

                    changeSetBean.execute(ds,realm);
                    ChangeSetRecord record = createNewChangeSetRecord(realm, changeSetBean);
                    changesetRecordRepo.save(ds, record);

                    ds.commitTransaction();
                } catch (Exception e) {
                    ds.abortTransaction();
                    throw new RuntimeException(e);
                }
            });
        });

    }

    private static @NotNull ChangeSetRecord createNewChangeSetRecord(String realm, ChangeSetBean changeSetBean) {
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
