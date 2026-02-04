package com.e2eq.framework.api.security;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.model.security.ApplicationRegistration;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.ApplicationRegistrationRequestRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.Datastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.ibm.icu.impl.Assert.fail;


@QuarkusTest
public class TestUserProfile extends BaseRepoTest {

    @Inject
    MigrationService migrationService;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    ApplicationRegistrationRequestRepo regRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    TestUtils testUtils;
    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;
    @Inject
    AuthProviderFactory authProviderFactory;


    @Test
    public void testMigration() throws Exception {

        try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {

            try {
                migrationService.checkMigrationRequired();
                Log.info("======   No migration required ======");
            } catch (DatabaseMigrationException ex) {
                ex.printStackTrace();
                Log.info("==== Migration required, running migrations ======");
            }

            // Always run migrations for system realm so AddSystemUserCredential runs (idempotent).
            // When checkMigrationRequired did not throw, we still need to ensure system user exists.
            Multi.createFrom().emitter(emitter -> {
                try {
                    migrationService.runAllUnRunMigrations(testUtils.getSystemRealm(), emitter);
                    migrationService.runAllUnRunMigrations(testUtils.getDefaultRealm(), emitter);
                    migrationService.runAllUnRunMigrations(testUtils.getTestRealm(), emitter);
                } finally {
                    emitter.complete();
                }
            }).collect().asList()
              .await().atMost(Duration.ofSeconds(120));

            // Use ignoreRules=true so we see the system credential even when test principal's rules would filter it out
            Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId(
                    envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm(), true);
            if (opCreds.isEmpty()) {
                // DB may already be at 1.0.4 with no pending changesets, so AddSystemUserCredential did not run. Create system user via auth provider.
                UserManagement userManagement = authProviderFactory.getUserManager();
                // Check again with ignoreRules so we don't create when credential exists but was hidden by rules (would cause E11000 duplicate key)
                boolean existsIgnoringRules = credentialRepo.findByUserId(
                        envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm(), true).isPresent();
                if (!existsIgnoringRules) {
                    DomainContext dc = DomainContext.builder()
                            .tenantId(envConfigUtils.getSystemTenantId())
                            .orgRefName(envConfigUtils.getSystemOrgRefName())
                            .defaultRealm(envConfigUtils.getSystemRealm())
                            .accountId(envConfigUtils.getSystemAccountNumber())
                            .build();
                    userManagement.createUser(
                            envConfigUtils.getSystemUserId(),
                            testUtils.getDefaultSystemPassword(),
                            false,
                            Set.of("system", "admin", "user"),
                            dc);
                }
                opCreds = credentialRepo.findByUserId(envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm(), true);
            }
            if (opCreds.isPresent()) {
                Log.debug("Found it");
            } else {
                Assertions.fail("No credentials found but they should have been post migration");
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    @Test void testCredentialsNoSecuritySession() {
        Datastore datastore = morphiaDataStoreWrapper.getDataStore(testUtils.getTestRealm());


        Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId( envConfigUtils.getTestUserId(), envConfigUtils.getSystemRealm(), true);
        if (opCreds.isPresent()) {
            Log.debug("Found it");
        } else {
            Assertions.fail("No credentials found");
        }
    }


    @Test
    public void testCreate() throws Exception {

        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {

            if (userProfileRepo == null) {
                Log.warn("userProfile is null?");
                Assertions.fail("userProfileService was not injected properly");
            } else {
                // find something missing.
                Optional<UserProfile> oProfile = userProfileRepo.findByRefName( "xxxxx", testUtils.getTestRealm());
                assert(!oProfile.isPresent());

                oProfile = userProfileRepo.findByRefName(testUtils.getTestUserId());
                if (!oProfile.isPresent()) {

                    Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId( envConfigUtils.getTestUserId(), envConfigUtils.getTestRealm(), true);

                    Log.info("About to execute");
                    UserProfile profile = new UserProfile();
                    profile.setCredentialUserIdPasswordRef(opCreds.get().createEntityReference());
                    profile.setUserId(testUtils.getTestUserId());
                    profile.setEmail(testUtils.getTestEmail());
                    profile.setRefName(testUtils.getTestUserId());
                    profile.setDataDomain(testUtils.getTestDataDomain());

                    //profile.setDataSegment(0);

                    profile = userProfileRepo.save(testUtils.getTestRealm(),profile);
                    assert (profile.getId() != null);


                    // check if getRefId works
                    oProfile = userProfileRepo.findByRefName(profile.getRefName(), testUtils.getTestRealm());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    oProfile = userProfileRepo.findById(oProfile.get().getId(), testUtils.getTestRealm());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    long count = userProfileRepo.delete(testUtils.getTestRealm(), profile);
                    assert (count == 1);
                }

                Log.info("Executed");
            }
        } finally {
            ruleContext.clear();
        }
    }

    @Test
    public void testGetUserProfileList() throws Exception {
        pContext = testUtils.getSystemPrincipalContext(testUtils.getSystemUserId(), roles);
        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {

            Log.infof("Default Realm:%s", SecurityContext.getPrincipalContext().get().getDefaultRealm());

            List<UserProfile> userProfiles = userProfileRepo.getList(testUtils.getTestRealm(), 0, 10, null, null);
            Assertions.assertTrue(!userProfiles.isEmpty());
            userProfiles.forEach((up) -> {
                Log.info(up.toString());
            });
        } finally {
            ruleContext.clear();
        }
    }

    @Test
    public void testGetFiltersWithLimit() {

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            //List<Filter> filters = new ArrayList<>();
            //Filter[] filterArray = userProfileRepo.getFilterArray(filters);
            List<UserProfile> userProfiles = userProfileRepo.getList(testUtils.getTestRealm(), 0,10,null, null);
            for (UserProfile up : userProfiles) {
                Log.info(up.toString());
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    @Test
    public void testGetFiltersWithNoLimit() {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            //List<Filter> filters = new ArrayList<>();
            //Filter[] filterArray = userProfileRepo.getFilterArray(filters);
            List<UserProfile> userProfiles = userProfileRepo.getAllList(testUtils.getTestRealm());
            for (UserProfile up : userProfiles) {
                Log.info(up.toString());
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    //@Test
    public void testGetRegistrationCollection() throws Exception {


        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         //   List<RegistrationRequest> registrationRequests = regRepo.getList(0, 10, null, null, null);
            List<ApplicationRegistration> registrationRequests = regRepo.getListByQuery(0,10, "credentialUserIdPasswordRef.entityRefName:tuser@test-b2bintegrator.com");
            Assertions.assertFalse(registrationRequests.isEmpty());
            registrationRequests.forEach((req) -> {
                Log.info(req.getId().toString() + ":" + req.getUserId() + ":" + req.getUserDisplayName());
            });
        } finally {
            ruleContext.clear();
        }
    }

  //  @Test
  // removed due to this should only be run with a "test database"
  // need to introduce the not ion of a profile and this test should only be executed under that profile.
    public void testRegistrationApproval() throws Exception {

        try(SecuritySession s = new SecuritySession(pContext, rContext)) {
            //   List<RegistrationRequest> registrationRequests = regRepo.getList(0, 10, null, null, null);
            List<ApplicationRegistration> registrationRequests = regRepo.getListByQuery(0,10, "userId:tuser@test-b2bintegrator.com&&status:UNAPPROVED");
            if (!registrationRequests.isEmpty()) {
                registrationRequests.forEach((req) -> {
                    Log.info(req.getId().toString() + ":" + req.getUserId() + ":" + req.getUserDisplayName());
                });

                regRepo.approveRequest(registrationRequests.get(0).getId().toString());

            }
        } catch ( SecurityCheckException ex ) {
            Log.error(ex.getMessage());
        }
        finally {
            ruleContext.clear();
        }

    }


}
