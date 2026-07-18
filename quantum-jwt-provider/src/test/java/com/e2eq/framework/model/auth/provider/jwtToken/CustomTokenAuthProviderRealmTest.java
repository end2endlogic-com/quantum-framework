package com.e2eq.framework.model.auth.provider.jwtToken;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
