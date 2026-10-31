package com.e2eq.framework.model.persistent.base;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RuleEdgeScopeTest {

    private static DataDomain caller() {
        DataDomain dd = new DataDomain();
        dd.setOrgRefName("org");
        dd.setAccountNum("0000000001");
        dd.setTenantId("tenant-a");
        dd.setDataSegment(3);
        return dd;
    }

    @Test
    void dataDomainScopeKeepsTheCallersDomain() {
        DataDomain c = caller();
        assertSame(c, RuleEdgeScope.DATA_DOMAIN.lookupDomain(c));
    }

    @Test
    void accountScopeKeepsOrganisationAccountAndSegmentAndOpensTheTenant() {
        DataDomain d = RuleEdgeScope.ACCOUNT.lookupDomain(caller());
        assertEquals(RuleEdgeScope.ANY_TENANT, d.getTenantId());
        assertEquals("org", d.getOrgRefName());
        assertEquals("0000000001", d.getAccountNum());
        assertEquals(3, d.getDataSegment());
    }
}
