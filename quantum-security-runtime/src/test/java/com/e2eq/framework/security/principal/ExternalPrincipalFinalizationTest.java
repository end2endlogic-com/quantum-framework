package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExternalPrincipalFinalizationTest {
    private final PrincipalFixture world = new PrincipalFixture();

    @Test
    void externalRolesAreTenantSpecificAndSuspensionAndTenantRemovalRefuse() {
        UserRealmRole membership = world.assignments.get("alice@acme|pooled");
        membership.setExternalTenantIds(List.of("tenant-b"));
        var request = world.identity("alice@acme").realm("pooled").tenantId("tenant-b").build();
        assertArrayEquals(new String[]{"external"}, world.resolver().resolve(request).context().getRoles());
        var member = world.resolver().resolve(world.identity("alice@acme").realm("pooled")
                .tenantId("tenant-a").build()).context();
        assertFalse(List.of(member.getRoles()).contains("external"));
        membership.setStatus(UserRealmRole.STATUS_SUSPENDED);
        assertThrows(PrincipalResolutionException.class, () -> world.resolver().resolve(request));
        membership.setStatus(UserRealmRole.STATUS_ACTIVE);
        membership.setAuthorizedTenantRegEx(".*");
        membership.setAuthorizedTenantIds(List.of("tenant-a"));
        assertThrows(PrincipalResolutionException.class, () -> world.resolver().resolve(request));
    }

    @Test
    void trustedClaimsUseDedicatedRealmsTenantEvenWhenLegacyContextDiffers() {
        var context = world.resolver().resolve(world.identity("alice@acme").build()).context();
        var membership = world.assignments.get("alice@acme|acme-D1");
        membership.setAuthorizedTenantIds(List.of("acme"));
        membership.setExternalTenantIds(List.of("acme"));
        var legacy = new com.e2eq.framework.model.securityrules.PrincipalContext.Builder()
                .withDefaultRealm("acme-D1").withUserId("alice@acme").withRoles(new String[]{"admin", "user"})
                .withDomainContext(com.e2eq.framework.model.security.DomainContext.builder()
                        .defaultRealm("acme-D1").tenantId("legacy").orgRefName("legacy").accountId("legacy").build())
                .build();
        var finalized = world.resolver().finalizeUserPrincipal(legacy);
        assertArrayEquals(new String[]{"external"}, finalized.getRoles());
        assertEquals("acme", finalized.getDomainContext().getTenantId());
        membership.setStatus(UserRealmRole.STATUS_SUSPENDED);
        var refusal = assertThrows(RemotePrincipalResolutionException.class,
                () -> world.resolver().finalizeUserPrincipal(legacy));
        assertEquals(403, refusal.getResponse().getStatus());
    }

    @Test
    void withoutASelectedTenantAnOutsiderAnywhereInTheRealmHoldsOnlyTheOutsiderRole() {
        UserRealmRole membership = world.assignments.get("alice@acme|pooled");
        var noTenant = new com.e2eq.framework.model.securityrules.PrincipalContext.Builder()
                .withDefaultRealm("pooled").withUserId("alice@acme").withRoles(new String[]{"admin", "user"})
                .build();
        assertArrayEquals(new String[]{"admin", "user"}, world.resolver().finalizeUserPrincipal(noTenant).getRoles());
        membership.setExternalTenantIds(List.of("tenant-b"));
        assertArrayEquals(new String[]{"external"}, world.resolver().finalizeUserPrincipal(noTenant).getRoles());
        membership.setStatus(UserRealmRole.STATUS_SUSPENDED);
        assertThrows(RemotePrincipalResolutionException.class, () -> world.resolver().finalizeUserPrincipal(noTenant));
    }

    @Test
    void dedicatedRealmAlsoStripsRolesButAbsentFieldPreservesMembers() {
        var request = world.identity("alice@acme").build();
        var before = world.resolver().resolve(request).context().getRoles();
        UserRealmRole membership = world.assignments.get("alice@acme|acme-D1");
        membership.setAuthorizedTenantIds(List.of("acme"));
        membership.setExternalTenantIds(List.of("acme"));
        assertArrayEquals(new String[]{"external"}, world.resolver().resolve(request).context().getRoles());
        membership.setStatus(UserRealmRole.STATUS_SUSPENDED);
        assertThrows(PrincipalResolutionException.class, () -> world.resolver().resolve(request));
        membership.setStatus(UserRealmRole.STATUS_ACTIVE);
        membership.setExternalTenantIds(null);
        assertArrayEquals(before, world.resolver().resolve(request).context().getRoles());
    }
}
