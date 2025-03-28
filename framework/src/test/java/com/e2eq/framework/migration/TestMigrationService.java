package com.e2eq.framework.migration;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.TestUtils;
import com.oracle.graal.python.builtins.objects.range.RangeNodesFactory;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.semver4j.Semver;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CreateUserPoolRequest;

import java.util.Optional;

@QuarkusTest
public class TestMigrationService extends BaseRepoTest {
    @Inject
    MigrationService migrationService;

    @Inject
    TestUtils testUtils;

    @Test
    public void testBasicSaveAndLoad() throws ReferentialIntegrityViolationException {
        DatabaseVersionRepo repo = migrationService.getDatabaseVersionRepo();
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            DatabaseVersion dbVersion;
            Optional<DatabaseVersion> odbVersion = repo.findByRefName("test-blank");
            if (odbVersion.isPresent()) {
                Log.infof("dbVersion: %s", odbVersion.get().toString());
            } else {
                dbVersion = new DatabaseVersion();
                dbVersion.setRefName("test-blank");
                dbVersion.setLastUpdated(new java.util.Date());
                repo.save(dbVersion);
            }

            Optional<DatabaseVersion> odbVersion2 = repo.findByRefName("test-blank");
            Assertions.assertTrue(odbVersion2.isPresent());
            Assertions.assertEquals(odbVersion2.get().getRefName(), "test-blank");

            odbVersion2.get().setLastUpdated(new java.util.Date());
            dbVersion = repo.save(odbVersion2.get());
            repo.delete(dbVersion);

            // now try with a semver
            Semver semver = new Semver("1.0.0");
            dbVersion = new DatabaseVersion();
            dbVersion.setRefName("test-blank");
            dbVersion.setLastUpdated(new java.util.Date());
            dbVersion = repo.save(dbVersion);

            repo.delete(dbVersion);

        }

    }

    @Test
    public void testMigrationRequiredLogic() throws ReferentialIntegrityViolationException {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {

            DatabaseVersionRepo dbVersionRepo = migrationService.getDatabaseVersionRepo();
            MorphiaSession session = dbVersionRepo.startSession(testUtils.getTestRealm());
            session.startTransaction();

            Optional<DatabaseVersion> odbv1 = dbVersionRepo.findByRefName(session, "test");
            Log.infof("odbv1: %s", odbv1.isPresent() ? odbv1.get().toString() : "not found");

            DatabaseVersion dbVersion = migrationService.saveDatabaseVersion(session,"test", "1.0.0");

            Assertions.assertFalse(migrationService.migrationRequired(session,"test", "1.0.0"));
            Assertions.assertTrue(migrationService.migrationRequired(session, "test", "1.0.1"));
            migrationService.saveDatabaseVersion(session, "test", "1.0.1");

            Assertions.assertFalse(migrationService.migrationRequired(session,"test", "1.0.1"));
            Assertions.assertTrue(migrationService.migrationRequired(session,"test", "1.0.2"));

            migrationService.getDatabaseVersionRepo().delete(session, dbVersion);
            session.commitTransaction();
        }
    }

    @Test
    public void testChangeBeans() {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            Log.info("---- All ChangeSetBeans ----");
            migrationService.getAllChangeSetBeans().forEach(csb -> Log.infof("ChangeSetBean: %s", csb.toString()));

            Log.info("---- All Pending ChangeSetBeans ----");
            migrationService.getAllPendingChangeSetBeans(testUtils.getTestRealm()).forEach(csb -> Log.infof("Pending %s ChangeSetBean: %s",testUtils.getTestRealm(),  csb.toString()));
            migrationService.getAllPendingChangeSetBeans(testUtils.getDefaultRealm()).forEach(csb -> Log.infof("Pending %s ChangeSetBean: %s", testUtils.getDefaultRealm(),csb.toString()));
            migrationService.getAllPendingChangeSetBeans(testUtils.getSystemRealm()).forEach(csb -> Log.infof("Pending %s ChangeSetBean: %s", testUtils.getSystemRealm(),csb.toString()));
        }
    }

    @Test
    public void testRunAllUnRunMigrations() {
        try (final SecuritySession ss = new SecuritySession(pContext, rContext)) {
            Log.infof("Running all unrun migrations for tenant: %s", testUtils.getTestTenantId());

             migrationService.runAllUnRunMigrations(testUtils.getTestRealm());

        }
    }
}
