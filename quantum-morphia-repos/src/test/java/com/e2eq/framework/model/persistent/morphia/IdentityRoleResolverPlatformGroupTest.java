package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Realm-wide authority from groups comes only from groups stored in the system realm with no
 * {@code ownerTenantId}. A tenant-owned group, or a group in a tenant realm, is never realm wide.
 */
class IdentityRoleResolverPlatformGroupTest {

    private static final String SYSTEM = "system-com";
    private static final String POOLED = "pooled-realm";

    private static UserGroup group(String applicationId, String ownerTenantId, String... roles) {
        UserGroup group = new UserGroup();
        group.setApplicationId(applicationId);
        group.setOwnerTenantId(ownerTenantId);
        group.setRoles(List.of(roles));
        return group;
    }

    private static IdentityRoleResolver resolver(List<UserGroup> systemGroups, List<UserGroup> pooledGroups) {
        IdentityRoleResolver resolver = new IdentityRoleResolver();
        resolver.envConfigUtils = mock(EnvConfigUtils.class);
        when(resolver.envConfigUtils.getSystemRealm()).thenReturn(SYSTEM);
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setUserId("issuer");
        credential.setSubject("issuer-subject");
        credential.setRoles(new String[0]);
        credential.setDomainContext(new DomainContext(new DataDomain("org", "0", "t", 0, "issuer"), POOLED));
        resolver.credentialRepo = mock(CredentialRepo.class);
        when(resolver.credentialRepo.findByUserId(eq("issuer"), eq(SYSTEM), eq(true))).thenReturn(Optional.of(credential));
        resolver.userProfileRepo = mock(UserProfileRepo.class);
        UserProfile profile = mock(UserProfile.class);
        when(profile.createEntityReference()).thenReturn(new EntityReference());
        when(resolver.userProfileRepo.getBySubject(anyString(), anyString())).thenReturn(Optional.of(profile));
        resolver.userGroupRepo = mock(UserGroupRepo.class);
        when(resolver.userGroupRepo.findByUserProfileRefWithIgnoreRules(eq(SYSTEM), any())).thenReturn(systemGroups);
        when(resolver.userGroupRepo.findByUserProfileRefWithIgnoreRules(eq(POOLED), any())).thenReturn(pooledGroups);
        resolver.userRealmRoleRepo = mock(UserRealmRoleRepo.class);
        resolver.directoryMode = Optional.empty();
        return resolver;
    }

    @Test
    void anOwnerlessGroupInATenantRealmIsNotRealmWide() {
        // The F5 bypass: a pooled tenant admin writes an "*" admin group without ownerTenantId.
        IdentityRoleResolver resolver = resolver(List.of(), List.of(group("*", null, "admin")));
        Set<String> roles = resolver.platformGroupRoles("issuer", POOLED, "helixor-code");
        assertTrue(roles.isEmpty(), roles.toString());
        verify(resolver.userGroupRepo, never()).findByUserProfileRefWithIgnoreRules(eq(POOLED), any());
    }

    @Test
    void aBlankOwnerGroupInATenantRealmIsNotRealmWide() {
        IdentityRoleResolver resolver = resolver(List.of(), List.of(group("*", "  ", "admin")));
        assertTrue(resolver.platformGroupRoles("issuer", POOLED, "helixor-code").isEmpty());
    }

    @Test
    void anOwnerlessSystemRealmGroupIsRealmWide() {
        IdentityRoleResolver resolver = resolver(
                List.of(group("*", null, "admin"), group("helixor-code", " ", "issuer")), List.of());
        assertEquals(Set.of("admin", "issuer"), resolver.platformGroupRoles("issuer", POOLED, "helixor-code"));
    }

    @Test
    void aTenantOwnedGroupInTheSystemRealmIsNotRealmWide() {
        // Round-5 review: a tenant admin's group saved without X-Realm is stored in the system realm,
        // owned by its tenant (Auth sets ownerTenantId). It grants within that tenant only.
        IdentityRoleResolver resolver = resolver(
                List.of(group("*", "tenant-a", "admin"), group("*", "null", "admin")), List.of());
        assertTrue(resolver.platformGroupRoles("issuer", POOLED, "helixor-code").isEmpty());
    }

    @Test
    void aSystemRealmGroupForAnotherApplicationDoesNotCount() {
        IdentityRoleResolver resolver = resolver(List.of(group("other-app", null, "admin")), List.of());
        assertTrue(resolver.platformGroupRoles("issuer", POOLED, "helixor-code").isEmpty());
    }

    @Test
    void aTenantOwnedGroupStillGrantsItsMembersInItsOwnRealm() {
        // Round-6 review: platform ownership decides realm-wide authority only, never ordinary role resolution.
        IdentityRoleResolver resolver = resolver(List.of(), List.of(group(null, "t", "editor")));
        java.util.Map<String, ?> sources = resolver.resolveRoleSources("issuer", POOLED, null, null);
        assertTrue(sources.containsKey("editor"), "tenant-owned group in its own realm dropped: " + sources);
    }

    @Test
    void aTenantOwnedSystemRealmGroupStillGrantsOnSignInButIsNotPlatformAuthority() {
        IdentityRoleResolver resolver = resolver(List.of(group(null, "t", "org-admin")), List.of());
        java.util.Map<String, ?> sources = resolver.resolveRoleSources("issuer", SYSTEM, null, null);
        assertTrue(sources.containsKey("org-admin"), "tenant-owned system-realm group dropped: " + sources);
        assertTrue(resolver.platformGroupRoles("issuer", SYSTEM, null).isEmpty());
    }

    @Test
    void aGroupAnotherTenantOwnsGrantsNothingToAMemberOfAnotherTenant() {
        // Round-7 review, item 1: tenant-a's admin writes a roles:[admin] group naming a member of tenant "t".
        IdentityRoleResolver resolver = resolver(List.of(group("*", "tenant-a", "admin")),
                List.of(group("*", "tenant-a", "admin")));
        java.util.Map<String, ?> sources = resolver.resolveRoleSources("issuer", POOLED, null, null);
        assertTrue(!sources.containsKey("admin"), "another tenant's group granted: " + sources);
        CredentialUserIdPassword credential = resolver.credentialRepo.findByUserId("issuer", SYSTEM, true).orElseThrow();
        assertTrue(!List.of(resolver.resolveEffectiveRoles(null, credential, POOLED, POOLED, null)).contains("admin"));
    }

    @Test
    void aSessionOfTheOwnerTenantCountsItsGroupAndDropsWhatOnlyTheHomeTenantsGroupsGave() {
        IdentityRoleResolver resolver = resolver(
                List.of(group("*", "tenant-a", "billing-admin"), group("*", "t", "editor"), group("*", null, "user")),
                List.of());
        CredentialUserIdPassword issuer = resolver.credentialRepo.findByUserId("issuer", SYSTEM, true).orElseThrow();
        String[] home = resolver.resolveEffectiveRoles(null, issuer, POOLED, POOLED, null);
        assertEquals(Set.of("editor", "user"), Set.of(home), "home tenant t: its own group and the platform group");
        assertEquals(Set.of("billing-admin", "user"),
                Set.of(resolver.withSessionTenantGroups(home, issuer, POOLED, null, "tenant-a")),
                "session tenant tenant-a: its group counts, the home tenant's no longer does");
        assertEquals(Set.of(home), Set.of(resolver.withSessionTenantGroups(home, issuer, POOLED, null, "t")));
        assertEquals(Set.of(home), Set.of(resolver.withSessionTenantGroups(home, null, POOLED, null, "tenant-a")),
                "without a local credential the provider's roles stand");
    }
}
