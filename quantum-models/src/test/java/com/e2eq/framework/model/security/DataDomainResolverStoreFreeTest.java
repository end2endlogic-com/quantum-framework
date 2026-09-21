package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataDomainResolverStoreFreeTest {

    private DataDomain createDataDomain(String org, String acc, String tenant, int segment, String owner) {
        return DataDomain.builder()
                .orgRefName(org != null ? org : "org1")
                .accountNum(acc != null ? acc : "acc1")
                .tenantId(tenant != null ? tenant : "tenant1")
                .dataSegment(segment)
                .ownerId(owner != null ? owner : "owner1")
                .build();
    }

    private final DataDomainResolver resolver = new DataDomainResolver() {
        @Override
        public DataDomain resolveForCreate(String functionalArea, String functionalDomain) {
            return createDataDomain("defaultOrg", "defaultAcc", "default-tenant", 0, "defaultOwner");
        }
    };

    @Test
    void testResolveForQuery_failClosedWhenPrincipalNull() {
        DataDomainResolution res = resolver.resolveForQuery(null, "Sales", "Orders", null);
        assertFalse(res.isResolved());
        assertEquals("No principal context provided for query resolution", res.reason());
        assertThrows(IllegalStateException.class, res::dataDomain);
    }

    @Test
    void testResolveForQuery_fallbackToPrincipalCredentialDomain() {
        DataDomain principalDD = createDataDomain("orgA", "100", "tenant-1", 0, "user@test.com");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("user@test.com")
                .withDataDomain(principalDD)
                .build();

        DataDomainResolution res = resolver.resolveForQuery(principal, "Sales", "Orders", null);
        assertTrue(res.isResolved());
        assertEquals("tenant-1", res.dataDomain().getTenantId());
        assertEquals("orgA", res.dataDomain().getOrgRefName());
    }

    @Test
    void testResolveForQuery_matchesFixedPolicyEntry() {
        DataDomain fixedDD = createDataDomain("fixedOrg", "999", "fixed-tenant", 1, "fixedOwner");

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(List.of(fixedDD));

        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(Map.of("Sales:Orders", entry));

        DataDomain principalDD = createDataDomain("pOrg", "pAcc", "principal-tenant", 0, "user@test.com");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("user@test.com")
                .withDataDomain(principalDD)
                .withDataDomainPolicy(policy)
                .build();

        DataDomainResolution res = resolver.resolveForQuery(principal, "Sales", "Orders", null);
        assertTrue(res.isResolved());
        assertEquals("fixed-tenant", res.dataDomain().getTenantId());
        assertEquals("fixedOrg", res.dataDomain().getOrgRefName());
        assertEquals(1, res.dataDomain().getDataSegment());
    }

    @Test
    void testResolveForHop_failClosedWhenPrincipalNullOrEdgeBlank() {
        DataDomainResolution res1 = resolver.resolveForHop(null, "placedBy", "Sales", "Orders");
        assertFalse(res1.isResolved());
        assertEquals("No principal context provided for edge traversal hop", res1.reason());

        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("user@test.com")
                .withDataDomain(createDataDomain("tOrg", "tAcc", "t1", 0, "user@test.com"))
                .build();

        DataDomainResolution res2 = resolver.resolveForHop(principal, "", "Sales", "Orders");
        assertFalse(res2.isResolved());
        assertEquals("Edge type must not be blank for traversal hop", res2.reason());
    }

    @Test
    void testResolveForHop_matchesEdgeSpecificPolicy() {
        DataDomain supplierDD = createDataDomain("supplierOrg", "500", "supplier-tenant", 0, "supplierOwner");

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(List.of(supplierDD));

        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(Map.of("edge/suppliedBy/Procurement:Suppliers", entry));

        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("customer@test.com")
                .withDataDomain(createDataDomain("cOrg", "cAcc", "customer-tenant", 0, "customer@test.com"))
                .withDataDomainPolicy(policy)
                .build();

        DataDomainResolution res = resolver.resolveForHop(principal, "suppliedBy", "Procurement", "Suppliers");
        assertTrue(res.isResolved());
        assertEquals("supplier-tenant", res.dataDomain().getTenantId());
        assertEquals("supplierOrg", res.dataDomain().getOrgRefName());
    }
}
