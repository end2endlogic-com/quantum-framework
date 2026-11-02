package com.e2eq.framework.model.auth.provider.jwtToken;

import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.SecurityUtils;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomTokenAuthProviderRealmTest {

    @Test
    void anEmailCredentialMayAddAPasswordAndStillValidates() {
        var credential = emailCredential();
        credential.setPassword("chosen-password-123");
        assertTrue(EncryptionUtils.checkPassword("chosen-password-123", credential.getPasswordHash()));
        credential.validate();
    }

    @Test
    void emailCredentialAdmitsPasswordLoginOnlyOnceAPasswordIsSet() {
        var credential = emailCredential();
        assertTrue(CustomTokenAuthProvider.authenticationMethodAdmitted(credential, true));
        assertFalse(CustomTokenAuthProvider.authenticationMethodAdmitted(credential, false));

        credential.setPassword("chosen-password-123");
        assertTrue(CustomTokenAuthProvider.authenticationMethodAdmitted(credential, false));
        assertTrue(CustomTokenAuthProvider.authenticationMethodAdmitted(credential, true),
            "setting a password keeps email sign-in");
    }

    @Test
    void passwordCredentialNeverGainsEmailLogin() {
        var credential = new CredentialUserIdPassword();
        credential.setCredentialType(CredentialType.PASSWORD);
        credential.setPassword("existing-password-123");
        assertTrue(CustomTokenAuthProvider.authenticationMethodAdmitted(credential, false));
        assertFalse(CustomTokenAuthProvider.authenticationMethodAdmitted(credential, true));
    }

    @Test
    void sessionsStartedAtOrBeforeAPasswordChangeNoLongerRefresh() {
        var credential = emailCredential();
        assertFalse(CustomTokenAuthProvider.endedByPasswordChange(credential, 1_000L));

        credential.setPasswordChangedAt(new Date(2_000_900L));
        assertTrue(CustomTokenAuthProvider.endedByPasswordChange(credential, 2_000_000L));
        assertTrue(CustomTokenAuthProvider.endedByPasswordChange(credential, 2_000_900L));
        assertFalse(CustomTokenAuthProvider.endedByPasswordChange(credential, 2_000_901L),
            "a sign-in just after the change, in the same second, keeps its session");
    }

    @Test
    void everyPasswordHashChangeRecordsWhenItHappened() {
        var credential = new CredentialUserIdPassword();
        assertEquals(null, credential.getPasswordChangedAt());

        credential.setPasswordHash("hash-1");
        Date first = credential.getPasswordChangedAt();
        assertTrue(first != null);

        credential.setPasswordChangedAt(new Date(0L));
        credential.setPasswordHash("hash-1");
        assertEquals(new Date(0L), credential.getPasswordChangedAt(), "an unchanged hash is not a change");

        credential.setPassword("another-password-123");
        assertTrue(credential.getPasswordChangedAt().after(new Date(0L)));
    }

    private static CredentialUserIdPassword emailCredential() {
        var credential = new CredentialUserIdPassword();
        credential.setCredentialType(CredentialType.EMAIL);
        return credential;
    }

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
