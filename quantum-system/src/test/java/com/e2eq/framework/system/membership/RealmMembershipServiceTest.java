package com.e2eq.framework.system.membership;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.system.config.QuantumModeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
class RealmMembershipServiceTest {

    @Test
    void controlPlaneUpdatePreservesExistingTenantDataDomainWhenOmitted() {
        RealmMembershipService service = new RealmMembershipService();
        service.quantumModeConfig = QuantumModeConfig.of("embedded", Optional.empty());
        service.systemDirectory = new SystemDirectory() {
            @Override public String systemRealmId() { return "quantum-system"; }
            @Override public Optional<Realm> findRealmByEmailDomain(String value) { return Optional.empty(); }
            @Override public Optional<Realm> findRealmByRefName(String value) { return Optional.empty(); }
            @Override public Realm registerRealm(Realm value) { return value; }
            @Override public Optional<CredentialUserIdPassword> findCredentialBySubject(String value) { return Optional.empty(); }
            @Override public Optional<CredentialUserIdPassword> findCredentialByUserId(String value) { return Optional.empty(); }
        };

        DataDomain tenantScope = DataDomain.builder()
            .tenantId("development")
            .orgRefName("HelixorAI")
            .accountNum("0000000001")
            .ownerId("system")
            .build();
        UserRealmRole existing = new UserRealmRole();
        existing.setUserId("mingardia@helixor.ai");
        existing.setRealmRefName("helixor-code-D1");
        existing.setDataDomain(tenantScope);
        UserRealmRole update = new UserRealmRole();
        update.setUserId(existing.getUserId());
        update.setRealmRefName(existing.getRealmRefName());
        update.setRoles(List.of("system", "admin", "user"));

        service.userRealmRoleRepo = new UserRealmRoleRepo() {
            @Override
            public Optional<UserRealmRole> findAssignmentForRealmWithIgnoreRules(
                    String userId, String realmRefName, String systemRealmId) {
                return Optional.of(existing);
            }

            @Override
            public UserRealmRole save(String realmId, UserRealmRole value) {
                return value;
            }
        };

        UserRealmRole saved = service.upsertUserRealmRole(update);

        assertEquals(tenantScope, saved.getDataDomain());
        assertEquals(update.getRoles(), saved.getRoles());
    }

    @Test
    void fullControlPlaneRoleUpdatePreservesTenantEntitlementAndIdentity() {
        var service = embeddedService();
        var existing = new UserRealmRole();
        existing.setId(new org.bson.types.ObjectId());
        existing.setVersion(8L);
        existing.setRefName("authoritative-assignment");
        existing.setUserId("user");
        existing.setSubject("stable-subject");
        existing.setRealmRefName("engineering");
        existing.setAuthorizedTenantRegEx("tenant-.*");
        existing.setDisplayName("User in Engineering");
        existing.setDataDomain(new DataDomain("org", "account", "tenant", 0, "owner"));
        service.userRealmRoleRepo = org.mockito.Mockito.mock(UserRealmRoleRepo.class);
        org.mockito.Mockito.when(service.userRealmRoleRepo.findAssignmentForRealmWithIgnoreRules(
            "user", "engineering", "system")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.when(service.userRealmRoleRepo.save(org.mockito.ArgumentMatchers.eq("system"),
            org.mockito.ArgumentMatchers.any(UserRealmRole.class))).thenAnswer(i -> i.getArgument(1));
        var wire = com.e2eq.framework.system.remote.ControlPlaneRealmMapper.toEntry(existing);
        wire.setRoles(List.of("admin"));
        var saved = service.upsertUserRealmRole(com.e2eq.framework.system.remote.ControlPlaneRealmMapper.fromEntry(wire));
        assertEquals("tenant-.*", saved.getAuthorizedTenantRegEx());
        assertEquals("authoritative-assignment", saved.getRefName());
        assertEquals("stable-subject", saved.getSubject());
        assertEquals("User in Engineering", saved.getDisplayName());
        assertEquals(8L, saved.getVersion());
        assertEquals(wire.getId(), saved.getId().toHexString());
        assertEquals(List.of("admin"), saved.getRoles());
    }

    @Test
    void fullControlPlaneMembershipUpdatePreservesProvisioningAndStorageScope() {
        var service = embeddedService();
        var existing = new com.e2eq.framework.model.security.RealmTenantMembership();
        existing.setId(new org.bson.types.ObjectId());
        existing.setRefName("authoritative-membership");
        existing.setRealmRefName("engineering");
        existing.setOrganizationRefName("org");
        existing.setRealmDisplayName("Engineering");
        existing.setRealmEmailDomain("example.test");
        existing.setDefaultAdminUserId("admin@example.test");
        existing.setRealmEditionRefName("standard");
        existing.setProvisioningMode("managed");
        existing.setSetupStatus("ready");
        existing.setSetupCompletionPercent(100);
        existing.setDisplayName("Organization in Engineering");
        var scope = new DataDomain("org", "account", "tenant", 0, "owner");
        existing.setDataDomain(scope);
        service.membershipRepo = org.mockito.Mockito.mock(com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo.class);
        org.mockito.Mockito.when(service.membershipRepo.findByRealmRefNameWithIgnoreRules("system", "engineering"))
            .thenReturn(List.of(existing));
        org.mockito.Mockito.when(service.membershipRepo.save(org.mockito.ArgumentMatchers.eq("system"),
            org.mockito.ArgumentMatchers.any(com.e2eq.framework.model.security.RealmTenantMembership.class)))
            .thenAnswer(i -> i.getArgument(1));
        var wire = com.e2eq.framework.system.remote.ControlPlaneRealmMapper.toEntry(existing);
        wire.setMembershipRole("participant");
        var saved = service.upsertMembership(com.e2eq.framework.system.remote.ControlPlaneRealmMapper.fromEntry(wire));
        assertEquals("Engineering", saved.getRealmDisplayName());
        assertEquals("example.test", saved.getRealmEmailDomain());
        assertEquals("admin@example.test", saved.getDefaultAdminUserId());
        assertEquals("standard", saved.getRealmEditionRefName());
        assertEquals("managed", saved.getProvisioningMode());
        assertEquals("ready", saved.getSetupStatus());
        assertEquals(100, saved.getSetupCompletionPercent());
        assertEquals("Organization in Engineering", saved.getDisplayName());
        assertEquals(scope, saved.getDataDomain());
        assertEquals("participant", saved.getMembershipRole());
    }

    private static RealmMembershipService embeddedService() {
        var service = new RealmMembershipService();
        service.quantumModeConfig = QuantumModeConfig.of("embedded", Optional.empty());
        service.systemDirectory = org.mockito.Mockito.mock(SystemDirectory.class);
        org.mockito.Mockito.when(service.systemDirectory.systemRealmId()).thenReturn("system");
        return service;
    }
}
