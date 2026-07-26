package com.e2eq.framework.api.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantRealmNamingTest {

    @Test
    void dedicatedRealmUsesAppDAndTenant() {
        assertEquals("helixor-code-D-end2endlogic",
            TenantRealmNaming.dedicatedRealm("Helixor Code", "end2endlogic"));
    }

    @Test
    void pooledRealmUsesAppPAndPodNumber() {
        assertEquals("helixor-code-P1",
            TenantRealmNaming.pooledRealm("Helixor Code", 1));
        assertEquals("helixor-code-P12",
            TenantRealmNaming.pooledRealm("helixor-code", 12));
    }

    @Test
    void pooledRealmRejectsInvalidPodNumber() {
        assertThrows(IllegalArgumentException.class,
            () -> TenantRealmNaming.pooledRealm("helixor-code", 0));
    }
}
