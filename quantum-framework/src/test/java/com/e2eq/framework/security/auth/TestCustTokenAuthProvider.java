package com.e2eq.framework.security.auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import com.e2eq.framework.model.security.auth.UserManagement;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.wildfly.common.Assert;

import java.util.Set;

@QuarkusTest
public class TestCustTokenAuthProvider extends BaseRepoTest {
    @Inject
    RuleContext ruleContext;

    @Inject
    AuthProviderFactory authProviderFactory;

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    TestUtils testUtils;



    @Test
    public void testCreateCustomUserInTestRealm() throws ReferentialIntegrityViolationException {
        if (authProvider.equals("custom")) {
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                DomainContext domainContext = DomainContext.builder()
                        .orgRefName(testUtils.getTestOrgRefName())
                        .defaultRealm(testUtils.getTestRealm())
                        .accountId(testUtils.getTestAccountNumber())
                        .tenantId(testUtils.getTestTenantId())
                        .build();
                AuthProvider authProvider = authProviderFactory.getAuthProvider();
                UserManagement userManager = authProviderFactory.getUserManager();

                userManager.removeUserWithUserId(testUtils.getTestRealm(),"testuser"); // remove if exists
                String subject = userManager.createUser(testUtils.getTestRealm(),"testuser", "test123456",  Set.of("user"), domainContext);
                Set<String> roles = userManager.getUserRolesForSubject(testUtils.getTestRealm(),subject);
                Assert.assertTrue(roles.contains("user"));
                AuthProvider.LoginResponse response = authProvider.login(testUtils.getTestRealm(),"testuser", "test123456");
                Assert.assertTrue(response.authenticated());
                Assert.assertTrue(userManager.subjectExists(testUtils.getTestRealm(),subject));
                userManager.assignRolesForSubject(testUtils.getTestRealm(),subject, Set.of("admin"));
                Assert.assertTrue(userManager.getUserRolesForSubject(testUtils.getTestRealm(),subject).contains("admin"));
                userManager.removeRolesForSubject(testUtils.getTestRealm(),subject, Set.of("admin"));
                Assert.assertFalse(userManager.getUserRolesForSubject(testUtils.getTestRealm(),subject).contains("admin"));
                userManager.removeUserWithSubject(testUtils.getTestRealm(),subject);
                Assert.assertFalse(userManager.subjectExists(testUtils.getTestRealm(),subject));
                Assert.assertFalse(userManager.userIdExists(testUtils.getTestRealm(),"testuser"));
            }
        }
    }

    @Test
    public void testCreateCustomUserInDefaultRealm() throws ReferentialIntegrityViolationException {
        if (authProvider.equals("custom")) {
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                DomainContext domainContext = DomainContext.builder()
                                                 .orgRefName(testUtils.getTestOrgRefName())
                                                 .defaultRealm(testUtils.getTestRealm())
                                                 .accountId(testUtils.getTestAccountNumber())
                                                 .tenantId(testUtils.getTestTenantId())
                                                 .build();
                AuthProvider authProvider = authProviderFactory.getAuthProvider();
                UserManagement userManager = authProviderFactory.getUserManager();
                userManager.removeUserWithUserId("testuser"); // remove if exists
                String subject = userManager.createUser("testuser", "test123456", Set.of("user"), domainContext);
                Set<String> roles = userManager.getUserRolesForSubject(subject);
                Assert.assertTrue(roles.contains("user"));
                AuthProvider.LoginResponse response = authProvider.login("testuser", "test123456");
                Assert.assertTrue(response.authenticated());
                Assert.assertTrue(userManager.subjectExists(subject));
                userManager.assignRolesForUserId("testuser", Set.of("admin"));
                Assert.assertTrue(userManager.getUserRolesForSubject(subject).contains("admin"));
                userManager.removeRolesForUserId("testuser", Set.of("admin"));
                Assert.assertFalse(userManager.getUserRolesForSubject(subject).contains("admin"));
                userManager.removeUserWithSubject(subject);
                Assert.assertFalse(userManager.subjectExists(subject));
            }
        }
    }


}
