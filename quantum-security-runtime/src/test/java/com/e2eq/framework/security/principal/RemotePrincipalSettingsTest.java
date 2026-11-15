package com.e2eq.framework.security.principal;

import com.e2eq.framework.security.principal.PrincipalSourceConfigurationException.Code;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        assertDoesNotThrow(() -> settings.check(LaunchMode.NORMAL, false));
    }

    @Test
    void remoteWithASourceAndAnApplicationIdStarts() {
        RemotePrincipalSettings settings = settings("remote", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        assertDoesNotThrow(() -> settings.check(LaunchMode.NORMAL, false));
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
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> settings.check(LaunchMode.NORMAL, false));
        assertTrue(failure.getMessage().contains("RemotePrincipalSource"), failure.getMessage());
    }

    @Test
    void remoteWithoutAnApplicationIdFailsStartup() {
        RemotePrincipalSettings settings = settings("remote", true);
        assertNull(settings.applicationId());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> settings.check(LaunchMode.NORMAL, false));
        assertTrue(failure.getMessage().contains("application-id"), failure.getMessage());
    }

    @Test
    void anUnknownValueFailsStartup() {
        RemotePrincipalSettings settings = settings("claims", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        assertThrows(IllegalStateException.class, () -> settings.check(LaunchMode.NORMAL, false));
        assertFalse(settings.remote());
    }

    @Test
    void anApplicationSessionRealmCannotBeCombinedWithRemote() {
        RemotePrincipalSettings settings = settings("remote", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        settings.sessionRealm = Optional.of("some-realm");
        assertThrows(IllegalStateException.class, () -> settings.check(LaunchMode.NORMAL, false));
    }

    @Test
    void remoteRefusesTrustedTokenClaims() {
        RemotePrincipalSettings settings = remoteSettings();
        settings.trustTokenClaims = true;
        assertRefused(settings, Code.PRINCIPAL_SOURCE_TRUSTS_TOKEN_CLAIMS);
    }

    @Test
    void remoteRefusesAudienceAdmission() {
        RemotePrincipalSettings expected = remoteSettings();
        expected.expectedAudience = Optional.of("helixor-di");
        assertRefused(expected, Code.PRINCIPAL_SOURCE_AUDIENCE_CONFIGURED);

        RemotePrincipalSettings verified = remoteSettings();
        verified.verifyAudiences = Optional.of("helixor-di");
        assertRefused(verified, Code.PRINCIPAL_SOURCE_AUDIENCE_CONFIGURED);

        RemotePrincipalSettings blank = remoteSettings();
        blank.verifyAudiences = Optional.of("  ");
        blank.expectedAudience = Optional.of("");
        assertDoesNotThrow(() -> blank.check(LaunchMode.NORMAL, true));
    }

    @Test
    void remoteRefusesLazyAuthentication() {
        RemotePrincipalSettings settings = remoteSettings();
        settings.proactiveAuthentication = false;
        assertRefused(settings, Code.PRINCIPAL_SOURCE_LAZY_AUTHENTICATION);
    }

    @Test
    void aDeploymentDeclaringRemoteCannotBeOverriddenOff() {
        RemotePrincipalSettings settings = settings(null, true);
        PrincipalSourceConfigurationException refused = assertThrows(PrincipalSourceConfigurationException.class,
                () -> settings.check(LaunchMode.NORMAL, true));
        assertEquals(Code.PRINCIPAL_SOURCE_NOT_REMOTE, refused.code());
        assertTrue(refused.getMessage().startsWith("PRINCIPAL_SOURCE_NOT_REMOTE: "), refused.getMessage());
        assertTrue(refused.getMessage().contains("QUANTUM_AUTH_PRINCIPAL_SOURCE"), refused.getMessage());
        // Dev and test may switch it off; an undeclared deployment keeps today's behaviour.
        assertDoesNotThrow(() -> settings.check(LaunchMode.DEVELOPMENT, true));
        assertDoesNotThrow(() -> settings.check(LaunchMode.TEST, true));
        assertDoesNotThrow(() -> settings.check(LaunchMode.NORMAL, false));
    }

    @Test
    void theDeclarationIsFoundForTheActiveProfileInAnySource() {
        String key = RemotePrincipalSettings.PROPERTY;
        assertTrue(RemotePrincipalSettings.declaresRemote(config("prod", Map.of("%prod,remote." + key, "remote"))));
        assertTrue(RemotePrincipalSettings.declaresRemote(config("remote", Map.of("%prod,remote." + key, "remote"))));
        assertTrue(RemotePrincipalSettings.declaresRemote(config("prod", Map.of("%prod." + key, " remote "))));
        assertTrue(RemotePrincipalSettings.declaresRemote(config("prod", Map.of(key, "remote"))));
        assertFalse(RemotePrincipalSettings.declaresRemote(config("dev", Map.of("%prod,remote." + key, "remote"))));
        assertFalse(RemotePrincipalSettings.declaresRemote(config("prod", Map.of(key, ""))));
        assertFalse(RemotePrincipalSettings.declaresRemote(config("prod", Map.of("%prodx." + key, "remote"))));
        assertFalse(RemotePrincipalSettings.declaresRemote(config("prod", Map.of("other.key", "remote"))));
        assertFalse(RemotePrincipalSettings.appliesToProfile("x" + key, Set.of("prod")));
    }

    @Test
    void anOverrideAboveTheDeclarationStillCountsAsDeclared() {
        // application.properties (250) declares remote; an environment-like source (300) unsets it.
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withProfile("prod")
                .withSources(new PropertiesConfigSource(
                        Map.of("%prod." + RemotePrincipalSettings.PROPERTY, "remote"), "application.properties", 250))
                .withSources(new PropertiesConfigSource(
                        Map.of(RemotePrincipalSettings.PROPERTY, "claims"), "env", 300))
                .build();
        assertEquals("claims", config.getRawValue(RemotePrincipalSettings.PROPERTY));
        assertTrue(RemotePrincipalSettings.declaresRemote(config));
    }

    @Test
    void anExpressionDeclaringRemoteCountsEvenWhenOverriddenOff() {
        String key = RemotePrincipalSettings.PROPERTY;
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withProfile("prod")
                .withSources(new PropertiesConfigSource(
                        Map.of("%prod." + key, "${PRINCIPAL_MODE:remote}"), "application.properties", 250))
                .withSources(new PropertiesConfigSource(Map.of(key, ""), "env", 300))
                .build();
        assertTrue(RemotePrincipalSettings.declaresRemote(config));
        SmallRyeConfig chosen = new SmallRyeConfigBuilder()
                .withProfile("prod")
                .withSources(new PropertiesConfigSource(
                        Map.of("%prod." + key, "${PRINCIPAL_MODE:claims}", "PRINCIPAL_MODE", "remote"),
                        "application.properties", 250))
                .build();
        assertTrue(RemotePrincipalSettings.declaresRemote(chosen));
        assertFalse(RemotePrincipalSettings.declaresRemote(config("prod", Map.of(key, "${PRINCIPAL_MODE:}"))));
    }

    @Test
    void refusalsCarryTheirCode() {
        RemotePrincipalSettings settings = settings("remote", false);
        settings.registrationApplicationId = Optional.of("helixor-di");
        assertRefused(settings, Code.PRINCIPAL_SOURCE_UNAVAILABLE);
        assertRefused(settings("remote", true), Code.PRINCIPAL_SOURCE_APPLICATION_MISSING);
        RemotePrincipalSettings unknown = settings("claims", true);
        assertRefused(unknown, Code.PRINCIPAL_SOURCE_INVALID);
        RemotePrincipalSettings session = remoteSettings();
        session.sessionSelectionPolicy = Optional.of("policy");
        assertRefused(session, Code.PRINCIPAL_SOURCE_SESSION_REALM);
    }

    private static void assertRefused(RemotePrincipalSettings settings, Code code) {
        PrincipalSourceConfigurationException refused = assertThrows(PrincipalSourceConfigurationException.class,
                () -> settings.check(LaunchMode.NORMAL, true));
        assertEquals(code, refused.code(), refused.getMessage());
        assertTrue(refused.getMessage().startsWith(code + ": "), refused.getMessage());
    }

    private static RemotePrincipalSettings remoteSettings() {
        RemotePrincipalSettings settings = settings("remote", true);
        settings.registrationApplicationId = Optional.of("helixor-di");
        return settings;
    }

    private static SmallRyeConfig config(String profile, Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withProfile(profile)
                .withSources(new PropertiesConfigSource(properties, "application.properties", 250))
                .build();
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
