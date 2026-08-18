package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlPlaneRealmMapperTest {

    @Test
    void mapsRealmTenancyModeWithoutConflatingItWithDeploymentType() {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName("pooled-realm");
        entry.setDatabaseName("pooled-realm");
        entry.setEmailDomain("pooled.example.test");
        entry.setDeploymentType("SHARED");
        entry.setTenancyMode("MULTI_TENANT");

        var realm = ControlPlaneRealmMapper.fromEntry(entry);

        assertEquals(RealmTenancyMode.MULTI_TENANT, realm.getTenancyMode());
        assertEquals("MULTI_TENANT",
            ControlPlaneRealmMapper.toEntry(realm).getTenancyMode());
    }

    @Test
    void mapsRealmMembershipWritesWithoutDroppingOwnership() {
        RealmMembershipEntry entry = new RealmMembershipEntry();
        entry.setRealmRefName("helixor-code-D1");
        entry.setOrganizationRefName("HelixorAI");
        entry.setAccountId("0000000001");
        entry.setTenantId("development");
        entry.setMembershipRole("owner");
        entry.setParticipationStatus("ACTIVE");

        RealmTenantMembership membership = ControlPlaneRealmMapper.fromEntry(entry);

        assertEquals("HelixorAI-helixor-code-D1", membership.getRefName());
        assertEquals("owner", membership.getMembershipRole());
        assertEquals("HelixorAI", ControlPlaneRealmMapper.toEntry(membership).getOrganizationRefName());
    }

    @Test
    void mapsUserRealmRoleWritesWithoutDroppingApplicationGrants() {
        UserRealmRoleEntry entry = new UserRealmRoleEntry();
        entry.setUserId("mingardia@helixor.ai");
        entry.setRealmRefName("helixor-code-D1");
        entry.setRoles(List.of("system", "admin", "user"));
        entry.setAuthorizedApplications(List.of("helixor-code", "helixor-reasoning-ux"));
        entry.setDefaultApplication("helixor-code");
        entry.setAuthorizedTenantIds(List.of("tenant-a", "tenant-b"));
        entry.setSponsoringOrgRefName("HelixorAI");
        entry.setStatus("active");

        UserRealmRole role = ControlPlaneRealmMapper.fromEntry(entry);

        assertEquals("mingardia@helixor.ai-helixor-code-D1", role.getRefName());
        assertEquals(entry.getAuthorizedApplications(), role.getAuthorizedApplications());
        assertEquals(entry.getAuthorizedTenantIds(), role.getAuthorizedTenantIds());
        assertEquals("HelixorAI", ControlPlaneRealmMapper.toEntry(role).getSponsoringOrgRefName());
    }
}
