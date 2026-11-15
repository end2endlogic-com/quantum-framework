package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where application bindings and installs may be read and written: only where the directory is
 * owned, as {@link DirectoryRouting} (QuantumModeConfig, the one parser of the mode keys) says.
 */
class ApplicationAccessDirectoryModeTest {

    @Test
    void theLocalDirectoryIsTheDefault() {
        ApplicationAccessDirectory unrouted = new ApplicationAccessDirectory();
        unrouted.init();
        assertTrue(unrouted.bindingsReadable(), "without quantum-system there is no remote directory");
        unrouted.requireLocalDirectory();
        assertTrue(directory(false).bindingsReadable());
    }

    @Test
    void aRemoteDirectoryRefusesReadsAndWrites() {
        ApplicationAccessDirectory directory = directory(true);
        assertFalse(directory.bindingsReadable());
        ApplicationAccessException write = assertThrows(ApplicationAccessException.class, directory::requireLocalDirectory);
        assertEquals(ApplicationAccessException.Code.DIRECTORY_REMOTE, write.code());
        // Never an empty list: that would read as "no binding" and let the realm assignment decide.
        ApplicationAccessException read = assertThrows(ApplicationAccessException.class,
            () -> directory.bindingsFor(new CredentialUserIdPassword(), "planner", null));
        assertEquals(ApplicationAccessException.Code.DIRECTORY_REMOTE, read.code());
    }

    private static ApplicationAccessDirectory directory(boolean remote) {
        ApplicationAccessDirectory directory = new ApplicationAccessDirectory();
        directory.routing(() -> remote);
        return directory;
    }
}
