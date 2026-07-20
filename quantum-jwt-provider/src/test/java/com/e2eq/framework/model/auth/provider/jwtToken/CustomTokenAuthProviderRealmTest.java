package com.e2eq.framework.model.auth.provider.jwtToken;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.util.SecurityUtils;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomTokenAuthProviderRealmTest {

    @Test
    void credentialRolesApplyOnlyToCredentialDefaultRealm() {
        String[] globalRoles = {"system", "admin", "user"};

        assertEquals(Set.of("system", "admin", "user"),
            CustomTokenAuthProvider.credentialRolesForRealm(
                globalRoles, "system-com", "system-com"));
        assertEquals(Set.of(),
            CustomTokenAuthProvider.credentialRolesForRealm(
                globalRoles, "system-com", "northstar-field-service-com"));
    }

    @Test
    void realmAssignmentCannotExpandBlankCredentialRealmBoundary() {
        CredentialUserIdPassword credential = CredentialUserIdPassword.builder()
            .userId("local-test")
            .subject("local-test")
            .domainContext(DomainContext.builder()
                .tenantId("home")
                .orgRefName("home")
                .accountId("home")
                .defaultRealm("home-realm")
                .build())
            .lastUpdate(new Date())
            .build();

        SecurityUtils securityUtils = new SecurityUtils();
        assertTrue(CustomTokenAuthProvider.credentialAuthorizesRealm(
            securityUtils, credential, "home-realm"));
        assertFalse(CustomTokenAuthProvider.credentialAuthorizesRealm(
            securityUtils, credential, "another-realm"));
    }
}
