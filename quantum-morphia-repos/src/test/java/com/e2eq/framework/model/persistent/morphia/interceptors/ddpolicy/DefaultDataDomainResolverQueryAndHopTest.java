package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.DataDomainResolution;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDataDomainResolverQueryAndHopTest {

    private DataDomain createDataDomain(String org, String acc, String tenant, int segment, String owner) {
        return DataDomain.builder()
                .orgRefName(org != null ? org : "org1")
                .accountNum(acc != null ? acc : "acc1")
                .tenantId(tenant != null ? tenant : "tenant1")
                .dataSegment(segment)
                .ownerId(owner != null ? owner : "owner1")
                .build();
    }

    private DefaultDataDomainResolver createResolver(DataDomainPolicy globalPolicy) {
        DefaultDataDomainResolver resolver = new DefaultDataDomainResolver();
        resolver.globalPolicyProvider = new GlobalDataDomainPolicyProvider() {
            @Override
            public Optional<DataDomainPolicy> getPolicy() {
                return Optional.ofNullable(globalPolicy);
            }
        };
        return resolver;
    }

    @Test
    void testResolveForQuery_failClosedWhenPrincipalNull() {
        DefaultDataDomainResolver resolver = createResolver(null);
        DataDomainResolution res = resolver.resolveForQuery(null, "Sales", "Orders", null);
        assertFalse(res.isResolved());
        assertEquals("No principal context provided for query resolution", res.reason());
    }

    @Test
    void testResolveForQuery_matchesPrincipalPolicyEntry() {
        DataDomain fixedDD = createDataDomain("corpOrg", "111", "corp-tenant", 2, "corpUser");
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(List.of(fixedDD));

        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(Map.of("Sales:Orders", entry));

        DataDomain principalDD = createDataDomain("credOrg", "222", "cred-tenant", 0, "principalUser");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("principalUser")
                .withDataDomain(principalDD)
                .withDataDomainPolicy(policy)
                .build();

        DefaultDataDomainResolver resolver = createResolver(null);
        DataDomainResolution res = resolver.resolveForQuery(principal, "Sales", "Orders", null);
        assertTrue(res.isResolved());
        assertEquals("corp-tenant", res.dataDomain().getTenantId());
        assertEquals(2, res.dataDomain().getDataSegment());
    }

    @Test
    void testResolveForQuery_fallsBackToGlobalPolicy() {
        DataDomain globalDD = createDataDomain("globalOrg", "333", "global-tenant", 0, "globalUser");
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(List.of(globalDD));

        DataDomainPolicy globalPolicy = new DataDomainPolicy();
        globalPolicy.setPolicyEntries(Map.of("Sales:Orders", entry));

        DataDomain principalDD = createDataDomain("credOrg", "222", "cred-tenant", 0, "principalUser");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("principalUser")
                .withDataDomain(principalDD)
                .build();

        DefaultDataDomainResolver resolver = createResolver(globalPolicy);
        DataDomainResolution res = resolver.resolveForQuery(principal, "Sales", "Orders", null);
        assertTrue(res.isResolved());
        assertEquals("global-tenant", res.dataDomain().getTenantId());
        assertEquals("globalOrg", res.dataDomain().getOrgRefName());
    }

    @Test
    void testResolveForQuery_fallsBackToCredentialDomain() {
        DataDomain principalDD = createDataDomain("credOrg", "222", "cred-tenant", 0, "principalUser");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("principalUser")
                .withDataDomain(principalDD)
                .build();

        DefaultDataDomainResolver resolver = createResolver(null);
        DataDomainResolution res = resolver.resolveForQuery(principal, "Sales", "Orders", null);
        assertTrue(res.isResolved());
        assertEquals("cred-tenant", res.dataDomain().getTenantId());
        assertEquals("credOrg", res.dataDomain().getOrgRefName());
    }

    @Test
    void testResolveForHop_failClosedWhenPrincipalNullOrEdgeBlank() {
        DefaultDataDomainResolver resolver = createResolver(null);
        DataDomainResolution res1 = resolver.resolveForHop(null, "placedBy", "Sales", "Orders");
        assertFalse(res1.isResolved());
        assertEquals("No principal context provided for edge traversal hop", res1.reason());

        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("principalUser")
                .withDataDomain(createDataDomain("o", "a", "t", 0, "u"))
                .build();

        DataDomainResolution res2 = resolver.resolveForHop(principal, "  ", "Sales", "Orders");
        assertFalse(res2.isResolved());
        assertEquals("Edge type must not be blank for traversal hop", res2.reason());
    }

    @Test
    void testResolveForHop_matchesEdgePolicyInGlobalProvider() {
        DataDomain supplierDD = createDataDomain("supplierOrg", "555", "supplier-tenant", 0, "supplierUser");
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(List.of(supplierDD));

        DataDomainPolicy globalPolicy = new DataDomainPolicy();
        globalPolicy.setPolicyEntries(Map.of("edge/suppliedBy/Procurement:Suppliers", entry));

        DataDomain principalDD = createDataDomain("custOrg", "777", "cust-tenant", 0, "custUser");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("custUser")
                .withDataDomain(principalDD)
                .build();

        DefaultDataDomainResolver resolver = createResolver(globalPolicy);
        DataDomainResolution res = resolver.resolveForHop(principal, "suppliedBy", "Procurement", "Suppliers");
        assertTrue(res.isResolved());
        assertEquals("supplier-tenant", res.dataDomain().getTenantId());
        assertEquals("supplierOrg", res.dataDomain().getOrgRefName());
    }
}
