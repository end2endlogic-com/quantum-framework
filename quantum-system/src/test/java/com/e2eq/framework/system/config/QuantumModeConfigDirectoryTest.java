package com.e2eq.framework.system.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * quantum.directory.mode decides where realms and memberships are read, separately from
 * whether the deployment owns a system realm; unset, the older routing is unchanged.
 */
class QuantumModeConfigDirectoryTest {

    @Test
    void unsetDirectoryModeKeepsMembershipsOnTheDeploymentMode() {
        assertFalse(QuantumModeConfig.of("embedded", Optional.empty()).membershipsRemote());
        assertTrue(QuantumModeConfig.of("remote", Optional.of("https://control.test")).membershipsRemote());
        assertEquals(Optional.empty(), QuantumModeConfig.of("embedded", Optional.empty()).directoryMode());
    }

    @Test
    void embeddedDeploymentCanReadTheDirectoryRemotely() {
        QuantumModeConfig config = QuantumModeConfig.of("embedded", Optional.empty(),
            Optional.of("Remote"), Optional.of("https://auth.test"));

        assertTrue(config.isEmbedded());
        assertTrue(config.membershipsRemote());
        assertEquals(Optional.of("remote"), config.directoryMode());
        assertEquals(Optional.of("https://auth.test"), config.directoryBaseUrl());
    }

    @Test
    void remoteDeploymentCanOwnItsDirectoryLocally() {
        QuantumModeConfig config = QuantumModeConfig.of("remote", Optional.of("https://control.test"),
            Optional.of("local"), Optional.empty());

        assertTrue(config.isRemote());
        assertFalse(config.membershipsRemote());
    }

    @Test
    void directoryBaseUrlFallsBackToTheOlderKey() {
        QuantumModeConfig config = QuantumModeConfig.of("embedded", Optional.of(" https://older.test "),
            Optional.of("remote"), Optional.of("  "));

        assertEquals(Optional.of("https://older.test"), config.directoryBaseUrl());
    }

    @Test
    void newBaseUrlWinsOverTheOlderKey() {
        QuantumModeConfig config = QuantumModeConfig.of("remote", Optional.of("https://older.test"),
            Optional.empty(), Optional.of("https://auth.test"));

        assertEquals(Optional.of("https://auth.test"), config.directoryBaseUrl());
    }

    @Test
    void remoteDirectoryWithoutAnEndpointFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> QuantumModeConfig.of("embedded", Optional.empty(),
            Optional.of("remote"), Optional.empty()));
    }

    @Test
    void remoteModeAcceptsOnlyTheNewEndpointKey() {
        QuantumModeConfig config = QuantumModeConfig.of("remote", Optional.empty(),
            Optional.empty(), Optional.of("https://auth.test"));

        assertEquals(Optional.of("https://auth.test"), config.directoryBaseUrl());
    }

    @Test
    void unknownDirectoryModeFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> QuantumModeConfig.of("embedded", Optional.empty(),
            Optional.of("sideways"), Optional.of("https://auth.test")));
    }

    @Test
    void directoryTokenPrefersTheNewKeyAndIgnoresBlanks() {
        QuantumModeConfig config = QuantumModeConfig.of("embedded", Optional.empty(),
            Optional.of("remote"), Optional.of("https://auth.test"), Optional.of(" "));
        config.systemServiceToken = Optional.of("older-token");
        assertEquals(Optional.of("older-token"), config.directoryToken());

        config.configuredDirectoryToken = Optional.of("new-token");
        assertEquals(Optional.of("new-token"), config.directoryToken());
    }
}
