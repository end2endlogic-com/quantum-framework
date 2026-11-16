package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalIdentityAttributes;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Token roles count only in the realm the principal source resolved them for
 * ({@link PrincipalIdentityAttributes#ROLES_REALM}), never a realm claim copied onto the identity.
 */
class IdentityRoleResolverRolesRealmTest {

    private static IdentityRoleResolver resolver() {
        IdentityRoleResolver resolver = new IdentityRoleResolver();
        resolver.envConfigUtils = mock(EnvConfigUtils.class);
        when(resolver.envConfigUtils.getSystemRealm()).thenReturn("system-com");
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setUserId("alice");
        credential.setRoles(new String[0]);
        credential.setDomainContext(new DomainContext(new DataDomain("org", "0", "t", 0, "alice"), "home-realm"));
        resolver.credentialRepo = mock(CredentialRepo.class);
        when(resolver.credentialRepo.findByUserId(eq("alice"), eq("system-com"), eq(true))).thenReturn(Optional.of(credential));
        resolver.userRealmRoleRepo = mock(UserRealmRoleRepo.class);
        when(resolver.userRealmRoleRepo.findActiveAssignmentForRealmWithIgnoreRules(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        resolver.userProfileRepo = mock(UserProfileRepo.class);
        when(resolver.userProfileRepo.getBySubject(anyString(), anyString())).thenReturn(Optional.empty());
        resolver.userGroupRepo = mock(UserGroupRepo.class);
        resolver.directoryMode = Optional.empty();
        return resolver;
    }

    private static SecurityIdentity identity(String attribute, String realm) {
        return QuarkusSecurityIdentity.builder().setPrincipal(() -> "alice").addRole("token-role")
                .addAttribute(attribute, realm).build();
    }

    @Test
    void tokenRolesCountInTheResolvedRolesRealm() {
        SecurityIdentity identity = identity(PrincipalIdentityAttributes.ROLES_REALM, "tenant-a");
        Set<String> inTenant = resolver().resolveRolesForIdentity("alice", "tenant-a", identity);
        Set<String> atHome = resolver().resolveRolesForIdentity("alice", "home-realm", identity);
        assertTrue(inTenant.contains("token-role"), inTenant.toString());
        assertFalse(atHome.contains("token-role"), atHome.toString());
    }

    @Test
    void rolesOfUnknownProvenanceCountNowhereWhenACredentialExists() {
        // A "realm" attribute is not provenance, and the home realm is never assumed.
        SecurityIdentity identity = identity("realm", "tenant-a");
        assertFalse(resolver().resolveRolesForIdentity("alice", "tenant-a", identity).contains("token-role"));
        assertFalse(resolver().resolveRolesForIdentity("alice", "home-realm", identity).contains("token-role"));
    }
}
