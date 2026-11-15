package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReservedExternalRoleRepoValidationTest {

    @Test
    void userRealmRoleRepoRefusesExternalOnReconcileAndFieldUpdate() {
        UserRealmRoleRepo repo = new UserRealmRoleRepo();
        UserRealmRole bad = UserRealmRole.builder()
            .userId("outsider@example.com")
            .realmRefName("pooled")
            .roles(List.of("external"))
            .build();

        assertThrows(IllegalArgumentException.class,
            () -> repo.reconcileServerOwnedState(null, bad, RefNameContract.WriteMode.MERGE));
        assertThrows(IllegalArgumentException.class,
            () -> repo.validateFieldUpdate("roles", List.of("user", "EXTERNAL")));
        assertThrows(IllegalArgumentException.class,
            () -> repo.validateFieldUpdate("externalTenantIds", List.of(" ")));
        assertDoesNotThrow(() -> repo.validateFieldUpdate("roles", List.of("user")));
        assertDoesNotThrow(() -> repo.validateFieldUpdate("externalTenantIds", List.of("org-b")));
    }

    @Test
    void credentialRepoRefusesExternalOnReconcileAndFieldUpdate() {
        CredentialRepo repo = new CredentialRepo();
        CredentialUserIdPassword bad = new CredentialUserIdPassword();
        bad.setRoles(new String[]{"user", "external"});

        assertThrows(IllegalArgumentException.class,
            () -> repo.reconcileServerOwnedState(null, bad, RefNameContract.WriteMode.MERGE));
        assertThrows(IllegalArgumentException.class,
            () -> repo.validateFieldUpdate("roles", new String[]{"External"}));
        assertDoesNotThrow(() -> repo.validateFieldUpdate("roles", new String[]{"user"}));
    }

    @Test
    void userGroupRepoRefusesExternalOnReconcileAndFieldUpdate() {
        UserGroupRepo repo = new UserGroupRepo();
        UserGroup bad = new UserGroup();
        bad.setRoles(List.of(" external "));

        assertThrows(IllegalArgumentException.class,
            () -> repo.reconcileServerOwnedState(null, bad, RefNameContract.WriteMode.MERGE));
        assertThrows(IllegalArgumentException.class,
            () -> repo.validateFieldUpdate("roles", List.of("external")));
        assertDoesNotThrow(() -> repo.validateFieldUpdate("roles", List.of("viewer")));
    }

    @Test
    void accessInviteRepoRefusesExternalOnReconcileAndFieldUpdate() {
        AccessInviteRepo repo = new AccessInviteRepo();
        AccessInvite bad = AccessInvite.builder()
            .invitedByUserId("admin@example.com")
            .tokenHash("hash")
            .expiresAt(new Date())
            .grantedRoles(List.of("external"))
            .build();

        assertThrows(IllegalArgumentException.class,
            () -> repo.reconcileServerOwnedState(null, bad, RefNameContract.WriteMode.MERGE));
        assertThrows(IllegalArgumentException.class,
            () -> repo.validateFieldUpdate("grantedRoles", List.of("EXTERNAL")));
        assertDoesNotThrow(() -> repo.validateFieldUpdate("grantedRoles", List.of("viewer")));
    }
}
