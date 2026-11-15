package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where application bindings and installs may be read and written: only where the directory is
 * owned. {@code quantum.directory.mode} decides; unset, {@code quantum.mode} does, as for realm
 * memberships.
 */
class ApplicationAccessDirectoryModeTest {

    @Test
    void theLocalDirectoryIsTheDefault() {
        ApplicationAccessDirectory directory = directory(Optional.empty(), Optional.empty());
        assertTrue(directory.bindingsReadable());
        directory.requireLocalDirectory();
        assertTrue(directory(Optional.of("local"), Optional.of("remote")).bindingsReadable(),
            "an explicit local directory wins over quantum.mode");
    }

    @Test
    void aRemoteDirectoryRefusesReadsAndWrites() {
        for (ApplicationAccessDirectory directory : new ApplicationAccessDirectory[]{
                directory(Optional.of("remote"), Optional.empty()),
                directory(Optional.of(" REMOTE "), Optional.of("embedded")),
                directory(Optional.empty(), Optional.of("remote"))}) {
            assertFalse(directory.bindingsReadable());
            ApplicationAccessException write = assertThrows(ApplicationAccessException.class, directory::requireLocalDirectory);
            assertEquals(ApplicationAccessException.Code.DIRECTORY_REMOTE, write.code());
            // Never an empty list: that would read as "no binding" and let the realm assignment decide.
            ApplicationAccessException read = assertThrows(ApplicationAccessException.class,
                () -> directory.bindingsFor(new CredentialUserIdPassword(), "planner", null));
            assertEquals(ApplicationAccessException.Code.DIRECTORY_REMOTE, read.code());
        }
    }

    private static ApplicationAccessDirectory directory(Optional<String> directoryMode, Optional<String> quantumMode) {
        ApplicationAccessDirectory directory = new ApplicationAccessDirectory();
        directory.directoryMode = directoryMode;
        directory.quantumMode = quantumMode;
        return directory;
    }
}
