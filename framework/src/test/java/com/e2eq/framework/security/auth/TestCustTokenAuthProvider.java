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

                userManager.removeUserWithUsername(testUtils.getTestRealm(),"testuser"); // remove if exists
                userManager.createUser(testUtils.getTestRealm(),"testuser", "test123456", "testuser", Set.of("user"), domainContext);
                Set<String> roles = userManager.getUserRoles(testUtils.getTestRealm(),"testuser");
                Assert.assertTrue(roles.contains("user"));
                AuthProvider.LoginResponse response = authProvider.login(testUtils.getTestRealm(),"testuser", "test123456");
                Assert.assertTrue(response.authenticated());
                Assert.assertTrue(userManager.usernameExists(testUtils.getTestRealm(),"testuser"));
                userManager.assignRoles(testUtils.getTestRealm(),"testuser", Set.of("admin"));
                Assert.assertTrue(userManager.getUserRoles(testUtils.getTestRealm(),"testuser").contains("admin"));
                userManager.removeRoles(testUtils.getTestRealm(),"testuser", Set.of("admin"));
                Assert.assertFalse(userManager.getUserRoles(testUtils.getTestRealm(),"testuser").contains("admin"));
                userManager.removeUserWithUsername(testUtils.getTestRealm(),"testuser");
                Assert.assertFalse(userManager.usernameExists(testUtils.getTestRealm(),"testuser"));
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

                userManager.removeUserWithUsername("testuser"); // remove if exists
                userManager.createUser("testuser", "test123456", "testuser", Set.of("user"), domainContext);
                Set<String> roles = userManager.getUserRoles("testuser");
                Assert.assertTrue(roles.contains("user"));
                AuthProvider.LoginResponse response = authProvider.login("testuser", "test123456");
                Assert.assertTrue(response.authenticated());
                Assert.assertTrue(userManager.usernameExists("testuser"));
                userManager.assignRoles("testuser", Set.of("admin"));
                Assert.assertTrue(userManager.getUserRoles("testuser").contains("admin"));
                userManager.removeRoles("testuser", Set.of("admin"));
                Assert.assertFalse(userManager.getUserRoles("testuser").contains("admin"));
                userManager.removeUserWithUsername("testuser");
                Assert.assertFalse(userManager.usernameExists("testuser"));
            }
        }
    }


}
