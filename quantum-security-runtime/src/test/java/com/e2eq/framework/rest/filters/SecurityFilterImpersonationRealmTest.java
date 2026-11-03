package com.e2eq.framework.rest.filters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * With delegated token claims, X-Realm on an impersonation request names the
 * realm the impersonation is checked against; it does not switch the token
 * holder's own context. Every other request keeps its X-Realm.
 */
class SecurityFilterImpersonationRealmTest {

    @Test
    void delegatedImpersonationDoesNotSwitchTheTokenHolder() {
        assertNull(SecurityFilter.baseRealmOverride("customer-realm", true, true, "system"));
    }

    @Test
    void theSystemRealmIsStillPassedOnSoItsRefusalStands() {
        assertEquals("system", SecurityFilter.baseRealmOverride("system", true, true, "system"));
        assertEquals("SYSTEM", SecurityFilter.baseRealmOverride("SYSTEM", true, true, "system"));
    }

    @Test
    void ordinaryRealmSwitchesAreUnchanged() {
        assertEquals("customer-realm", SecurityFilter.baseRealmOverride("customer-realm", true, false, "system"));
        assertEquals("customer-realm", SecurityFilter.baseRealmOverride("customer-realm", false, true, "system"));
        assertEquals("customer-realm", SecurityFilter.baseRealmOverride("customer-realm", false, false, "system"));
        assertNull(SecurityFilter.baseRealmOverride(null, true, true, "system"));
    }
}
