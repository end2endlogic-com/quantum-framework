package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantDefaultsTest {

    @Test
    void candidatesAreTheCredentialTenantThenThePolicyDefault() {
        CredentialUserIdPassword credential = credential("home", policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "fixed"));
        assertEquals(List.of("home", "fixed"), TenantDefaults.candidates(credential));
        assertEquals(List.of(), TenantDefaults.candidates(null));
    }

    @Test
    void onlyAFixedStarStarEntryNamesAPolicyDefault() {
        assertEquals("fixed", TenantDefaults.policyDefaultTenant(policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "fixed")));
        assertNull(TenantDefaults.policyDefaultTenant(policy(DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL, "fixed")));
        assertNull(TenantDefaults.policyDefaultTenant(policy(DataDomainPolicyEntry.ResolutionMode.FIXED, null)));
        assertNull(TenantDefaults.policyDefaultTenant(null));
        DataDomainPolicy other = policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "fixed");
        other.setPolicyEntries(new HashMap<>(Map.of("sales:*", other.getPolicyEntries().get("*:*"))));
        assertNull(TenantDefaults.policyDefaultTenant(other));
    }

    @Test
    void theAuthorizedDefaultIsTheFirstCandidateTheAssignmentAuthorizes() {
        CredentialUserIdPassword credential = credential("home", policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "b"));
        assertEquals(Optional.of("b"), TenantDefaults.authorizedDefault(credential, grant("a", "b")));
        assertEquals(Optional.of("home"), TenantDefaults.authorizedDefault(credential, grant("home", "b")));
        assertEquals(Optional.empty(), TenantDefaults.authorizedDefault(credential, grant("a", "c")));
        UserRealmRole pattern = grant();
        pattern.setAuthorizedTenantRegEx("*");
        assertEquals(Optional.of("home"), TenantDefaults.authorizedDefault(credential, pattern));
    }

    @Test
    void severalTenantsMeansTwoIdsOrAPattern() {
        assertFalse(TenantDefaults.grantsSeveralTenants(grant("a")));
        assertFalse(TenantDefaults.grantsSeveralTenants(grant("a", " a ")));
        assertTrue(TenantDefaults.grantsSeveralTenants(grant("a", "b")));
        UserRealmRole pattern = grant();
        pattern.setAuthorizedTenantRegEx("acme-.*");
        assertTrue(TenantDefaults.grantsSeveralTenants(pattern));
        assertFalse(TenantDefaults.grantsSeveralTenants(null));
    }

    @Test
    void aGrantWidensWhenItAddsATenantOrChangesThePattern() {
        assertTrue(TenantDefaults.widensTenantGrant(grant("a"), grant("a", "b")));
        assertFalse(TenantDefaults.widensTenantGrant(grant("a", "b"), grant("a", "b")));
        assertFalse(TenantDefaults.widensTenantGrant(grant("a", "b"), grant("a")));
        assertTrue(TenantDefaults.widensTenantGrant(null, grant("a", "b")));
        assertFalse(TenantDefaults.widensTenantGrant(null, grant("a")));
        UserRealmRole pattern = grant("a");
        pattern.setAuthorizedTenantRegEx("acme-.*");
        assertTrue(TenantDefaults.widensTenantGrant(grant("a"), pattern));
        UserRealmRole samePattern = grant("a");
        samePattern.setAuthorizedTenantRegEx("acme-.*");
        assertFalse(TenantDefaults.widensTenantGrant(pattern, samePattern));
    }

    private static UserRealmRole grant(String... tenants) {
        return UserRealmRole.builder().userId("u").realmRefName("pooled").authorizedTenantIds(List.of(tenants)).build();
    }

    private static CredentialUserIdPassword credential(String tenant, DataDomainPolicy policy) {
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setDomainContext(DomainContext.builder()
                .tenantId(tenant).defaultRealm("home-realm").orgRefName("o").accountId("a").build());
        credential.setDataDomainPolicy(policy);
        return credential;
    }

    private static DataDomainPolicy policy(DataDomainPolicyEntry.ResolutionMode mode, String tenant) {
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(mode);
        if (tenant != null) {
            entry.setDataDomains(List.of(DataDomain.builder().tenantId(tenant).orgRefName("o").accountNum("a").ownerId("x").build()));
        }
        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(new HashMap<>(Map.of("*:*", entry)));
        return policy;
    }
}
