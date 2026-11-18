package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 5/6 review: a realm named by caller input (X-Realm, a path or query parameter) reached a
 * datastore before any check; an unknown name created a database. Only the configured system and
 * default realms and registered realms are admitted; reserved database names never are, and an
 * existing database is not enough.
 */
class RequestRealmAdmissionTest {

    private RequestRealmAdmission admission;
    private SystemDirectory directory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RealmRefNames.forget();
        admission = new RequestRealmAdmission();
        admission.envConfigUtils = mock(EnvConfigUtils.class);
        when(admission.envConfigUtils.getSystemRealm()).thenReturn("system-com");
        when(admission.envConfigUtils.getDefaultRealm()).thenReturn("default-com");
        directory = mock(SystemDirectory.class);
        Realm pooled = new Realm();
        pooled.setRefName("acme-P1");
        when(directory.findRealmByRefName(anyString())).thenAnswer(call ->
                ((String) call.getArgument(0)).equalsIgnoreCase("acme-P1") ? Optional.of(pooled) : Optional.empty());
        Instance<SystemDirectory> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(directory);
        admission.systemDirectory = instance;
    }

    @Test
    void theConfiguredSystemAndDefaultRealmsAndRegisteredRealmsAreAdmitted() {
        assertEquals("system-com", admission.admit("system-com"));
        assertEquals("default-com", admission.admit("default-com"));
        assertEquals("acme-P1", admission.admit("acme-P1"));
        assertEquals("acme-P1", admission.admit("ACME-p1"), "answered as the registered refName");
    }

    @Test
    void anUnregisteredRealmIsRefusedTyped() {
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> admission.admit("zz-flood-1-abcd"));
        assertEquals(403, refused.getResponse().getStatus());
        assertEquals("REALM_NOT_AUTHORIZED", refused.code());
    }

    @Test
    void reservedDatabaseNamesAreNeverAdmittedNorLookedUp() {
        for (String reserved : new String[] {"admin", "local", "config", "ADMIN", "quantum-coordination-shared"}) {
            assertTrue(admission.admitted(reserved).isEmpty(), reserved);
        }
        verify(directory, never()).findRealmByRefName(anyString());
    }

    @Test
    void aMisshapenRealmIsRefusedAndNeverLookedUp() {
        for (String bad : new String[] {"bad.realm", "a b", "x".repeat(64), "-lead"}) {
            assertTrue(admission.admitted(bad).isEmpty(), bad);
        }
        verify(directory, never()).findRealmByRefName(anyString());
    }

    @Test
    void aCatalogOutageIsATyped503() {
        when(directory.findRealmByRefName(anyString())).thenThrow(new IllegalStateException("down"));
        RemotePrincipalResolutionException unavailable = assertThrows(RemotePrincipalResolutionException.class,
                () -> admission.admit("acme-P1"));
        assertEquals(503, unavailable.getResponse().getStatus());
    }

    @Test
    void theRefusedRealmAttributeIsReadByItsLiteralName() {
        // SecurityFilter and RefusedRealmForbiddenExceptionMapper spell it as a literal (the
        // token-claim scan reads a computed getAttribute name as a claim read).
        assertEquals("quantum-realm-refused", RealmRefNames.REFUSED_REALM_ATTRIBUTE);
    }
}
