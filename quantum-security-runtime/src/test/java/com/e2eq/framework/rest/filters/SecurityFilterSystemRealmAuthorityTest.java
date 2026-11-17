package com.e2eq.framework.rest.filters;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delegated-identity gate for the system realm: which signed roles admit a token
 * to the cross-tenant system data domain ({@code quantum.security.system-realm.authority-roles}).
 */
class SecurityFilterSystemRealmAuthorityTest {

    private static final List<String> DEFAULT = List.of("system");

    @Test
    void onlySystemAdmitsByDefault() {
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"user", "system"}, DEFAULT));
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"SYSTEM"}, DEFAULT));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[] {"admin", "user"}, DEFAULT));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[] {"registry-reader"}, DEFAULT));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[0], DEFAULT));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(null, DEFAULT));
    }

    @Test
    void aServiceMayNameFurtherAuthorityRoles() {
        List<String> configured = Arrays.asList("system", " registry-reader ");
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"registry-reader"}, configured));
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"Registry-Reader"}, configured));
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"system"}, configured));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[] {"admin"}, configured));
    }

    @Test
    void anEmptyOrBlankSettingNeverWidensTheGate() {
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"system"}, List.of()));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[] {"admin"}, List.of()));
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"system"}, Arrays.asList(" ", "")));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[] {"registry-reader"}, Arrays.asList(" ", "")));
        assertTrue(SecurityFilter.hasSystemRealmAuthority(new String[] {"system"}, null));
        assertFalse(SecurityFilter.hasSystemRealmAuthority(new String[] {"admin"}, null));
    }
}
