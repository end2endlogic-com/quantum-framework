package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.securityrules.RuleContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

@QuarkusTest
public class TestSecurityRules {

    @Inject
    RuleContext ruleContext;

    @Inject
    UserProfileRepo userProfileRepo;

    UserProfile getOrCreateTestUserProfile() {
        Optional<UserProfile> ouserProfile = userProfileRepo.findByRefName("test-user");
        if (ouserProfile.isPresent()) {
            return ouserProfile.get();
        } else {
            UserProfile userProfile = new UserProfile();
            userProfile.setRefName("test-user");



            userProfileRepo.save(userProfile);
            return userProfile;
        }
    }

    @Test
    public void testBasics() {
        // create a simple model where we have an account admin, and a normal user.
        // ensure that the account admin can see all user profiles, but the normal user can only see their own profile.



    }
}
