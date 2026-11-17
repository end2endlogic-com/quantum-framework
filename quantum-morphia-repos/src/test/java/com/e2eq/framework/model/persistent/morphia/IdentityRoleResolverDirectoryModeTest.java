package com.e2eq.framework.model.persistent.morphia;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** With a remote directory the local UserRealmRole copy is not read for roles. */
class IdentityRoleResolverDirectoryModeTest {

    private static boolean authoritative(Optional<String> mode) {
        IdentityRoleResolver resolver = new IdentityRoleResolver();
        resolver.directoryMode = mode;
        return resolver.localRealmAssignmentsAuthoritative();
    }

    @Test
    void localAssignmentsCountWhenTheDirectoryIsLocalOrUnset() {
        assertTrue(authoritative(Optional.empty()));
        assertTrue(authoritative(Optional.of("local")));
    }

    @Test
    void localAssignmentsAreIgnoredWhenTheDirectoryIsRemote() {
        assertFalse(authoritative(Optional.of("remote")));
        assertFalse(authoritative(Optional.of(" Remote ")));
    }
}
