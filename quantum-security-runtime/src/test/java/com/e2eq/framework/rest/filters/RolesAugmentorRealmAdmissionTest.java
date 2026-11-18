package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.security.principal.RealmRefNames;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round-5 review: the roles augmentor looked up credentials in the store of whatever realm
 * {@code X-Realm} named, before any check: a misshapen name was a 500, and a well-shaped unknown
 * name opened (and created) a database. It now admits only the system realm or a registered realm.
 */
class RolesAugmentorRealmAdmissionTest {

    private RolesAugmentor augmentor;
    private SystemDirectory directory;

    @BeforeEach
    void setUp() {
        RealmRefNames.forget();
        augmentor = new RolesAugmentor();
        augmentor.envConfigUtils = mock(EnvConfigUtils.class);
        when(augmentor.envConfigUtils.getSystemRealm()).thenReturn("system-com");
        directory = mock(SystemDirectory.class);
        Realm pooled = new Realm();
        pooled.setRefName("acme-P1");
        when(directory.findRealmByRefName(anyString())).thenAnswer(call ->
                ((String) call.getArgument(0)).equalsIgnoreCase("acme-P1") ? Optional.of(pooled) : Optional.empty());
        augmentor.systemDirectory = directory;
    }

    @Test
    void theSystemRealmAndRegisteredRealmsAreAdmitted() {
        assertTrue(augmentor.admittedRealm("system-com"));
        assertTrue(augmentor.admittedRealm("acme-P1"));
    }

    @Test
    void anUnregisteredRealmIsNotAdmitted() {
        assertFalse(augmentor.admittedRealm("zz-flood-1-abcd"));
    }

    @Test
    void aMisshapenRealmIsNotAdmittedAndNeverLookedUp() {
        for (String bad : new String[] {"bad.realm", "a b", "x".repeat(64), "-lead"}) {
            assertFalse(augmentor.admittedRealm(bad), bad);
        }
        verify(directory, never()).findRealmByRefName(anyString());
    }

    @Test
    void aCatalogOutageAdmitsNothing() {
        SystemDirectory down = mock(SystemDirectory.class);
        when(down.findRealmByRefName(anyString())).thenThrow(new IllegalStateException("down"));
        augmentor.systemDirectory = down;
        assertFalse(augmentor.admittedRealm("acme-P1"));
    }

    @Test
    void theSecurityFilterReadsTheRefusedRealmAttributeByItsLiteralName() {
        // SecurityFilter spells the attribute as a literal (the token-claim scan reads a computed
        // getAttribute name as a claim read); the two must stay the same.
        assertEquals("quantum-realm-refused", RealmRefNames.REFUSED_REALM_ATTRIBUTE);
    }
}
