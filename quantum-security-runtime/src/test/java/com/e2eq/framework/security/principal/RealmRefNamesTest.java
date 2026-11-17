package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A requested realm in another case is the registered realm; nothing else is invented. */
class RealmRefNamesTest {

    private static SystemDirectory directoryWith(String refName) {
        Realm realm = new Realm();
        realm.setRefName(refName);
        SystemDirectory directory = mock(SystemDirectory.class);
        when(directory.findRealmByRefName(anyString())).thenReturn(Optional.empty());
        when(directory.findRealmByRefName(refName)).thenReturn(Optional.of(realm));
        return directory;
    }

    @Test
    void anotherCaseIsTheRegisteredRefName() {
        SystemDirectory directory = directoryWith("acme-shared");
        assertEquals("acme-shared", RealmRefNames.canonicalOrSame(directory, "ACME-Shared"));
        assertEquals(Optional.of("acme-shared"), RealmRefNames.registered(directory, " ACME-SHARED "));
    }

    @Test
    void aLowerCaseNameIsUsedAsIsWithoutALookup() {
        SystemDirectory directory = directoryWith("acme-shared");
        assertEquals("acme-shared", RealmRefNames.canonicalOrSame(directory, "acme-shared"));
        verify(directory, never()).findRealmByRefName(anyString());
    }

    @Test
    void anUnknownRealmIsLeftForTheTypedRealmChecks() {
        SystemDirectory directory = directoryWith("acme-shared");
        assertEquals("NO-SUCH", RealmRefNames.canonicalOrSame(directory, "NO-SUCH"));
        assertTrue(RealmRefNames.registered(directory, "NO-SUCH").isEmpty());
        assertTrue(RealmRefNames.registered(null, "ACME").isEmpty());
    }
}
