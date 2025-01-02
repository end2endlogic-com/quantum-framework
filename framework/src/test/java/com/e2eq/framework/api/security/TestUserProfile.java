package com.e2eq.framework.api.security;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.ApplicationRegistrationRequestRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;


@QuarkusTest
public class TestUserProfile  {

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    ApplicationRegistrationRequestRepo regRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    RuleContext ruleContext;


    @Test
    public void testCredentials() throws Exception {
        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.systemUserId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
        try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId(SecurityUtils.systemUserId);
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
        Optional<CredentialUserIdPassword> opCreds = credentialRepo.findByUserId(SecurityUtils.systemUserId);
        if (opCreds.isPresent()) {
            Log.debug("Found it");
        } else {
            Assertions.fail("No credentials found");
        }
    }


    @Test
    public void testCreate() throws Exception {

        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.systemUserId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {

            if (userProfileRepo == null) {
                Log.warn("userProfile is null?");
                Assertions.fail("userProfileService was not injected properly");
            } else {
                // find something missing.
                Optional<UserProfile> oProfile = userProfileRepo.findByRefName("xxxxx");
                assert(!oProfile.isPresent());

                oProfile = userProfileRepo.findByRefName(TestUtils.systemUserId);
                if (!oProfile.isPresent()) {
                    Log.info("About to execute");
                    UserProfile profile = new UserProfile();
                    profile.setUserName(TestUtils.name);
                    profile.setEmail(TestUtils.email);
                    profile.setUserId(TestUtils.systemUserId);
                    profile.setRefName(profile.getUserId());
                    profile.setDataDomain(TestUtils.dataDomain);

                    //profile.setDataSegment(0);

                    profile = userProfileRepo.save(profile);
                    assert (profile.getId() != null);


                    // check if getRefId works
                    oProfile = userProfileRepo.findByRefName(profile.getRefName());
                    if (!oProfile.isPresent()) {
                        assert (false);
                    }

                    oProfile = userProfileRepo.findById(oProfile.get().getId().toString());
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
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "view");
        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.systemUserId);
        try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
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
        TestUtils.initRules(ruleContext, "security", "userProfile", TestUtils.systemUserId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "LocationList", "save");

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
        TestUtils.initRules(ruleContext, "security", "userProfile", TestUtils.systemUserId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "LocationList", "save");

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

        String[] roles = {"admin"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext("onboarding", "registration", "view");
        TestUtils.initRules(ruleContext, "onboarding", "registration", "admin@b2bintegrator.com");


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
        String[] roles = {"admin"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(SecurityUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext("onboarding", "registration", "update");
        TestUtils.initRules(ruleContext, "onboarding", "registration", "admin@b2bintegrator.com");


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
