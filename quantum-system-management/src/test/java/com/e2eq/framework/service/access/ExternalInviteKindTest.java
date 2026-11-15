package com.e2eq.framework.service.access;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.*;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalInviteKindTest {
    private AccessInviteService service;
    private final DataDomain partition = new DataDomain("org", "account", "tenant", 0, "person");

    @BeforeEach
    void setUp() {
        service = new AccessInviteService();
        service.quantumModeConfig = QuantumModeConfig.of("embedded", Optional.empty());
        service.userRealmRoleRepo = mock(UserRealmRoleRepo.class);
        service.realmMembershipService = mock(RealmMembershipService.class);
        service.systemDirectory = mock(SystemDirectory.class);
        service.securityUtils = mock(SecurityUtils.class);
        var environment = mock(EnvConfigUtils.class);
        when(service.securityUtils.getEnvConfigUtils()).thenReturn(environment);
        when(environment.getSystemRealm()).thenReturn("system");
        when(service.securityUtils.getSystemPrincipalContext()).thenReturn(new PrincipalContext.Builder()
                .withDefaultRealm("system").withUserId("system").withDataDomain(partition)
                .withScope("systemGenerated").withRoles(new String[]{"system"}).build());
        when(service.securityUtils.getSystemSecurityResourceContext()).thenReturn(new ResourceContext.Builder()
                .withRealm("system").build());
        var realm = new Realm();
        realm.setRefName("realm");
        realm.setTenancyMode(RealmTenancyMode.MULTI_TENANT);
        when(service.systemDirectory.findRealmByRefName("realm")).thenReturn(Optional.of(realm));
        when(service.realmMembershipService.realmsForUser("person")).thenReturn(List.of());
        when(service.realmMembershipService.upsertUserRealmRole(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void externalCreateChecksPermissionAndRejectsGrantedRolesBeforeProvisioning() {
        service.credentialRepo = mock(com.e2eq.framework.model.persistent.morphia.CredentialRepo.class);
        var credential = new CredentialUserIdPassword();
        credential.setUserId("person");
        credential.setDomainContext(DomainContext.builder().defaultRealm("realm").tenantId("tenant").orgRefName("org").accountId("account").build());
        when(service.credentialRepo.findByUserIdIgnoreCaseIgnoreRules("person", "system"))
                .thenReturn(Optional.of(credential));
        service.externalMembershipAuthorization = mock(com.e2eq.framework.security.runtime.ExternalMembershipAuthorization.class);
        var caller = new PrincipalContext.Builder().withDefaultRealm("realm").withUserId("person")
                .withDataDomain(partition).withScope("AUTHENTICATED").withRoles(new String[]{"custom-inviter"}).build();
        var request = com.e2eq.framework.rest.requests.AccessInviteRequest.builder()
                .kind("external").email("outside@example.test").grantedRoles(List.of("admin")).build();
        try (var ignored = SecurityCallScope.open(caller, new ResourceContext.Builder().withRealm("realm").build())) {
            doThrow(new jakarta.ws.rs.ForbiddenException()).when(service.externalMembershipAuthorization)
                    .require(caller, "realm", "tenant");
            assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> service.createInvite("realm", "person", request));
            doNothing().when(service.externalMembershipAuthorization).require(caller, "realm", "tenant");
            assertEquals(AccessInviteException.Code.INVALID_REQUEST,
                    assertThrows(AccessInviteException.class, () -> service.createInvite("realm", "person", request)).getCode());
        }
        verify(service.externalMembershipAuthorization, times(2)).require(caller, "realm", "tenant");
    }

    @Test
    void externalAcceptanceAddsBothTenantListsAndLeavesCredentialRolesAlone() throws Exception {
        var credential = new CredentialUserIdPassword();
        credential.setUserId("person");
        credential.setRoles(new String[]{"user", "admin"});
        var saved = accept(credential, "external");
        assertEquals(List.of("tenant"), saved.getExternalTenantIds());
        assertEquals(List.of("tenant"), saved.getAuthorizedTenantIds());
        assertArrayEquals(new String[]{"user", "admin"}, credential.getRoles());
        assertTrue(saved.getRoles().isEmpty());
    }

    @Test
    void neitherInviteKindCanConvertAnExistingMembership() throws Exception {
        var prior = UserRealmRole.builder().userId("person").realmRefName("realm")
                .authorizedTenantIds(List.of("tenant")).build();
        when(service.realmMembershipService.realmsForUser("person")).thenReturn(List.of(prior));
        var credential = new CredentialUserIdPassword();
        credential.setUserId("person");
        assertEquals(AccessInviteException.Code.INVITE_MEMBERSHIP_KIND_CONFLICT,
                assertThrows(AccessInviteException.class, () -> accept(credential, "external")).getCode());
        prior.setExternalTenantIds(List.of("tenant"));
        assertEquals(AccessInviteException.Code.INVITE_MEMBERSHIP_KIND_CONFLICT,
                assertThrows(AccessInviteException.class, () -> accept(credential, null)).getCode());
        verify(service.realmMembershipService, never()).upsertUserRealmRole(any());
    }

    private UserRealmRole accept(CredentialUserIdPassword credential, String kind) throws Exception {
        var invite = AccessInvite.builder().kind(kind).scope(InviteScope.builder().dataDomain(partition).build()).build();
        var grant = InviteGrant.builder().assignmentDataDomain(partition).newAssignmentTenantIds(List.of("tenant"))
                .addTenantIds(List.of("tenant")).roles(List.of()).sponsoringOrgRefName("org").build();
        var method = AccessInviteService.class.getDeclaredMethod("ensureRealmAssignment", String.class,
                CredentialUserIdPassword.class, InviteGrant.class, AccessInvite.class);
        method.setAccessible(true);
        try {
            return (UserRealmRole) method.invoke(service, "realm", credential, grant, invite);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }
}
