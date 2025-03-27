package com.e2eq.framework.security.auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.provider.cognito.CognitoAuthProvider;
import com.e2eq.framework.model.security.auth.provider.jwtToken.CustomTokenAuthProvider;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
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
    CustomTokenAuthProvider customTokenAuthProvider;

    @Inject
    CognitoAuthProvider cognitoAuthProvider;

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    TestUtils testUtils;

    @Test
    public void testCreateCustomUser() throws ReferentialIntegrityViolationException {

        if (authProvider.equals("custom")) {
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                DomainContext domainContext = DomainContext.builder()
                        .orgRefName(testUtils.getTestOrgRefName())
                        .defaultRealm(testUtils.getTestRealm())
                        .accountId(testUtils.getTestAccountNumber())
                        .tenantId(testUtils.getTestTenantId())
                        .build();

                customTokenAuthProvider.createUser("testuser", "test123456", Set.of("user"), domainContext);
                Set<String> roles = customTokenAuthProvider.getUserRoles("testuser");
                Assert.assertTrue(roles.contains("user"));
                AuthProvider.LoginResponse response = customTokenAuthProvider.login("testuser", "test123456");
                Assert.assertTrue(response.authenticated());
                Assert.assertTrue(customTokenAuthProvider.userExists("testuser"));
                customTokenAuthProvider.assignRoles("testuser", Set.of("admin"));
                Assert.assertTrue(customTokenAuthProvider.getUserRoles("testuser").contains("admin"));
                customTokenAuthProvider.removeRoles("testuser", Set.of("admin"));
                Assert.assertFalse(customTokenAuthProvider.getUserRoles("testuser").contains("admin"));
                customTokenAuthProvider.removeUser("testuser");
                Assert.assertFalse(customTokenAuthProvider.userExists("testuser"));
            }
        }
    }

    @Test
    public void testCreateCognitoUser() throws ReferentialIntegrityViolationException {
        if (authProvider.equals("cognito")) {
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                DomainContext domainContext = DomainContext.builder()
                        .orgRefName(testUtils.getTestOrgRefName())
                        .defaultRealm(testUtils.getTestRealm())
                        .accountId(testUtils.getTestAccountNumber())
                        .tenantId(testUtils.getTestTenantId())
                        .build();


                cognitoAuthProvider.createUser("testuser2@end2endlogic.com", "P@zzw@rd321", Set.of("user"), domainContext);
                Set<String> roles = cognitoAuthProvider.getUserRoles("testuser2@end2endlogic.com");
                Assert.assertTrue(roles.contains("user"));
                AuthProvider.LoginResponse response = cognitoAuthProvider.login("testuser2@end2endlogic.com", "P@zzw@rd321");
                Assert.assertTrue(response.authenticated());
                Assert.assertTrue(cognitoAuthProvider.userExists("testuser2@end2endlogic.com"));
                cognitoAuthProvider.assignRoles("testuser2@end2endlogic.com", Set.of("admin"));
                Assert.assertTrue(cognitoAuthProvider.getUserRoles("testuser2@end2endlogic.com").contains("admin"));
                cognitoAuthProvider.removeRoles("testuser2@end2endlogic.com", Set.of("admin"));
                Assert.assertFalse(cognitoAuthProvider.getUserRoles("testuser2@end2endlogic.com").contains("admin"));
                cognitoAuthProvider.removeUser("testuser2@end2endlogic.com");
                Assert.assertFalse(cognitoAuthProvider.userExists("testuser2@end2endlogic.com"));
            }
        }
    }
}

