package com.e2eq.framework.security.principal;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemotePrincipalSettingsTest {

    @Test
    void unsetIsTheExistingBehaviourAndNeedsNothing() {
        RemotePrincipalSettings settings = settings(null, false);
        assertFalse(settings.remote());
        assertDoesNotThrow(settings::validate);
    }

    @Test
    void remoteWithASourceAndAnApplicationIdStarts() {
        RemotePrincipalSettings settings = settings("remote", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        assertDoesNotThrow(settings::validate);
        assertTrue(settings.remote());
        assertEquals("helixor-di", settings.applicationId());
    }

    @Test
    void theSessionApplicationIdWinsOverTheRegistrationId() {
        RemotePrincipalSettings settings = settings("remote", true);
        settings.sessionApplicationId = Optional.of(" session-app ");
        settings.registrationApplicationId = Optional.of("registered-app");
        assertEquals("session-app", settings.applicationId());
    }

    @Test
    void remoteWithoutASourceBeanFailsStartup() {
        RemotePrincipalSettings settings = settings("remote", false);
        settings.registrationApplicationId = Optional.of("helixor-di");
        IllegalStateException failure = assertThrows(IllegalStateException.class, settings::validate);
        assertTrue(failure.getMessage().contains("RemotePrincipalSource"), failure.getMessage());
    }

    @Test
    void remoteWithoutAnApplicationIdFailsStartup() {
        RemotePrincipalSettings settings = settings("remote", true);
        assertNull(settings.applicationId());
        IllegalStateException failure = assertThrows(IllegalStateException.class, settings::validate);
        assertTrue(failure.getMessage().contains("application-id"), failure.getMessage());
    }

    @Test
    void anUnknownValueFailsStartup() {
        RemotePrincipalSettings settings = settings("claims", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        assertThrows(IllegalStateException.class, settings::validate);
        assertFalse(settings.remote());
    }

    @Test
    void anApplicationSessionRealmCannotBeCombinedWithRemote() {
        RemotePrincipalSettings settings = settings("remote", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        settings.sessionRealm = Optional.of("some-realm");
        assertThrows(IllegalStateException.class, settings::validate);
    }

    @SuppressWarnings("unchecked")
    private static RemotePrincipalSettings settings(String mode, boolean sourceDeployed) {
        RemotePrincipalSettings settings = new RemotePrincipalSettings();
        settings.principalSource = Optional.ofNullable(mode);
        Instance<RemotePrincipalSource> sources = mock(Instance.class);
        when(sources.isUnsatisfied()).thenReturn(!sourceDeployed);
        when(sources.isAmbiguous()).thenReturn(false);
        settings.sources = sources;
        return settings;
    }
}
