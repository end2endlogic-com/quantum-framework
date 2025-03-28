package com.e2eq.framework.api.security;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.ApplicationRegistrationRequestRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.Datastore;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;


@QuarkusTest
public class TestUserProfile extends BaseRepoTest {

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    ApplicationRegistrationRequestRepo regRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    TestUtils testUtils;
    @Inject
    MorphiaDataStore morphiaDataStore;


    @Test
    public void testSystemCredentials() throws Exception {

        try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId(securityUtils.getSystemUserId());
            if (opCreds.isPresent()) {
                Log.debug("Found it");
            } else {
                Assertions.fail("No credentials found");
            }
        }
        finally {
            ruleContext.clear();
        }
    }

    @Test void testCredentialsNoSecuritySession() {
        Datastore datastore = morphiaDataStore.getDataStore(testUtils.getTestRealm());
        Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId(securityUtils.getTestRealm(),securityUtils.getSystemUserId());
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
                Optional<UserProfile> oProfile = userProfileRepo.findByRefName( "xxxxx");
                assert(!oProfile.isPresent());

                oProfile = userProfileRepo.findByRefName(testUtils.getTestUserId());
                if (!oProfile.isPresent()) {
                    Log.info("About to execute");
                    UserProfile profile = new UserProfile();
                    profile.setUserName(testUtils.getTestUserId());
                    profile.setEmail(testUtils.getTestEmail());
                    profile.setUserId(testUtils.getTestUserId());
                    profile.setRefName(testUtils.getTestUserId());
                    profile.setDataDomain(testUtils.getTestDataDomain());

                    //profile.setDataSegment(0);

                    profile = userProfileRepo.save(profile);
                    assert (profile.getId() != null);


                    // check if getRefId works
                    oProfile = userProfileRepo.findByRefName(profile.getRefName());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    oProfile = userProfileRepo.findById(oProfile.get().getId());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    long count = userProfileRepo.delete(profile);
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

            List<UserProfile> userProfiles = userProfileRepo.getList(0, 10, null, null);
            Assertions.assertTrue(!userProfiles.isEmpty());
            userProfiles.forEach((up) -> {
                Log.info(up.getId().toString() + ":" + up.getUserId() + ":" + up.getUserName());
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
            List<UserProfile> userProfiles = userProfileRepo.getList(0,10,null, null);
            for (UserProfile up : userProfiles) {
                Log.info(up.getId().toString() + ":" + up.getUserId() + ":" + up.getUserName());
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
            List<UserProfile> userProfiles = userProfileRepo.getAllList();
            for (UserProfile up : userProfiles) {
                Log.info(up.getId().toString() + ":" + up.getUserId() + ":" + up.getUserName());
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
            List<ApplicationRegistration> registrationRequests = regRepo.getListByQuery(0,10, "userId:tuser@test-b2bintegrator.com");
            Assertions.assertFalse(registrationRequests.isEmpty());
            registrationRequests.forEach((req) -> {
                Log.info(req.getId().toString() + ":" + req.getUserId() + ":" + req.getUserName());
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
                    Log.info(req.getId().toString() + ":" + req.getUserId() + ":" + req.getUserName());
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
