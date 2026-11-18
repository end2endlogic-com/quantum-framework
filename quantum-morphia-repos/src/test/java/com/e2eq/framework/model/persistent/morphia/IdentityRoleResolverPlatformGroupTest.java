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
}
