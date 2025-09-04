package com.e2eq.framework.security.auth;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestSecurityAnnotationTest {

    @Inject
    TestUtils testUtils;

    @Inject
    RuleContext ruleContext;

    @Inject
    com.e2eq.framework.model.persistent.morphia.CredentialRepo credentialRepo;

    @org.junit.jupiter.api.AfterEach
    void clearThreadLocals() {
        // Defensive cleanup to avoid leaking context across tests in this class
        com.e2eq.framework.model.securityrules.SecurityContext.clear();
    }

    @Test
    @TestSecurity(user = "test@system.com", roles = {"user"})
    public void testAuthenticatedWithTestSecurityDefaultRealm() {
        // Do NOT create SecuritySession. Trigger repo logic that uses MorphiaRepo.getFilterArray,
        // which should lazily build PrincipalContext from SecurityIdentity.
        try {
            credentialRepo.findByUserId("nonexistent@end2endlogic.com", testUtils.getTestRealm(), false);
        } catch (Exception ignored) {
           Assertions.fail("Should not have thrown exception");
        }
        Assertions.assertTrue(SecurityContext.getPrincipalContext().isPresent(), "PrincipalContext should be present via lazy fallback");
        Assertions.assertEquals("test@system.com", SecurityContext.getPrincipalContext().get().getUserId());
        // ResourceContext should also be set to DEFAULT_ANONYMOUS_CONTEXT by the repo fallback
        Assertions.assertTrue(com.e2eq.framework.model.securityrules.SecurityContext.getResourceContext().isPresent());
    }

    @Test
    @TestSecurity(user = "another-nonexistent@system.com", roles = {"admin","user"})
    public void testAuthenticatedWithoutSecuritySession_RepoFallback() {
        try {
            credentialRepo.findByUserId("test@system.com", testUtils.getTestRealm(), false);
        } catch (Throwable notFound) {
           // should throw because nonexistent is not configured
           return;
        }


       Assertions.fail("Should have thrown exception");

    }
}
