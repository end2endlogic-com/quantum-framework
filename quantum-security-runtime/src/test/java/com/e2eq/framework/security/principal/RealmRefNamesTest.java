package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A requested realm in another case is the registered realm; nothing else is invented. */
class RealmRefNamesTest {

    @BeforeEach
    void freshCache() {
        RealmRefNames.forget();
    }

    /** A catalog that matches ignoring case, as LocalSystemDirectory does. */
    private static SystemDirectory catalogWith(String refName) {
        Realm realm = new Realm();
        realm.setRefName(refName);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(directory.findRealmByRefName(anyString())).thenAnswer(call -> {
            String asked = call.getArgument(0);
            return asked.equalsIgnoreCase(refName) ? Optional.of(realm) : Optional.empty();
        });
        return directory;
    }

    @Test
    void anotherCaseIsTheRegisteredRefNameEvenAMixedCaseOne() {
        // A pooled realm's refName is mixed case (app-P1): neither its upper- nor its lower-case
        // form is the database name.
        SystemDirectory directory = catalogWith("helixor-code-P1");
        assertEquals("helixor-code-P1", RealmRefNames.canonicalOrSame(directory, "HELIXOR-CODE-P1"));
        assertEquals("helixor-code-P1", RealmRefNames.canonicalOrSame(directory, "helixor-code-p1"));
        assertEquals(Optional.of("helixor-code-P1"), RealmRefNames.registered(directory, " Helixor-Code-p1 "));
    }

    @Test
    void anOlderExactMatchDirectoryIsAskedAgainInLowerCase() {
        Realm realm = new Realm();
        realm.setRefName("acme-shared");
        SystemDirectory exact = mock(SystemDirectory.class);
        when(exact.findRealmByRefName(anyString())).thenReturn(Optional.empty());
        when(exact.findRealmByRefName("acme-shared")).thenReturn(Optional.of(realm));
        assertEquals("acme-shared", RealmRefNames.canonicalOrSame(exact, "ACME-Shared"));
    }

    @Test
    void anUnknownRealmIsLeftForTheTypedRealmChecksAndCachedAsUnknown() {
        SystemDirectory directory = catalogWith("acme-shared");
        for (int i = 0; i < 5; i++) {
            assertEquals("NO-SUCH", RealmRefNames.canonicalOrSame(directory, "NO-SUCH"));
        }
        // One exact and one lower-case lookup, then the cached "unknown" answers every request.
        verify(directory, times(1)).findRealmByRefName("NO-SUCH");
        verify(directory, times(1)).findRealmByRefName("NO-SUCH".toLowerCase(Locale.ROOT));
        assertTrue(RealmRefNames.registered(null, "ACME").isEmpty());
    }

    @Test
    void theCacheIsBoundedAndDroppedOnRegistration() {
        SystemDirectory directory = catalogWith("acme-shared");
        for (int i = 0; i < RealmRefNames.MAX_ENTRIES + 10; i++) {
            RealmRefNames.registered(directory, "unknown-" + i);
        }
        RealmRefNames.registered(directory, "LATE");
        RealmRefNames.forget();
        RealmRefNames.registered(directory, "LATE");
        verify(directory, times(2)).findRealmByRefName("LATE");
    }

    @Test
    void aDirectoryOutageIsTyped503NotA500() {
        SystemDirectory down = mock(SystemDirectory.class);
        when(down.findRealmByRefName(anyString())).thenThrow(new IllegalStateException("control plane unreachable"));
        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> RealmRefNames.canonicalOrSame(down, "ACME"));
        assertEquals(503, refused.status());
        assertEquals(RemotePrincipalResolutionException.UNAVAILABLE, refused.code());
    }
}
