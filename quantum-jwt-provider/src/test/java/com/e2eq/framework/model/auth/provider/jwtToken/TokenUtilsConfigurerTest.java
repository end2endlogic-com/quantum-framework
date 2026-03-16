package com.e2eq.framework.model.auth.provider.jwtToken;

import io.smallrye.config.ConfigValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenUtilsConfigurerTest {

    @AfterEach
    void resetDefaults() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
    }

    @Test
    void allowsDefaultFallbackInTestProfile() {
        assertDoesNotThrow(() -> TokenUtilsConfigurer.validateConfiguredKeyLocations(
                List.of("test"),
                configValue("quantum.jwt.private-key-location", null, null),
                configValue("quantum.jwt.public-key-location", null, null)));
    }

    @Test
    void rejectsMissingProductionKeyConfiguration() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> TokenUtilsConfigurer.validateConfiguredKeyLocations(
                List.of("prod"),
                configValue("quantum.jwt.private-key-location", null, null),
                configValue("quantum.jwt.public-key-location", "file:/run/secrets/public.pem", "PropertiesConfigSource[source=app]")));
        assertEquals(
                "Production JWT startup requires quantum.jwt.private-key-location to be explicitly configured; default JWT key fallback is not allowed",
                ex.getMessage());
    }

    @Test
    void allowsEnvironmentConfiguredDefaultFilenamesInProduction() {
        assertDoesNotThrow(() -> TokenUtilsConfigurer.validateConfiguredKeyLocations(
                List.of("prod"),
                configValue("quantum.jwt.private-key-location", "privateKey.pem", "EnvConfigSource"),
                configValue("quantum.jwt.public-key-location", "publicKey.pem", "EnvConfigSource")));
    }

    @Test
    void configuresTokenUtilsWhenKeysAreExplicitlyProvided() {
        TokenUtils.configure("privateKey.pem", "publicKey.pem");

        ConfigValue privateKey = configValue("quantum.jwt.private-key-location", "file:/run/secrets/privateKey.pem", "EnvConfigSource");
        ConfigValue publicKey = configValue("quantum.jwt.public-key-location", "file:/run/secrets/publicKey.pem", "EnvConfigSource");

        TokenUtilsConfigurer.validateConfiguredKeyLocations(List.of("prod"), privateKey, publicKey);
        TokenUtils.configure(privateKey.getValue(), publicKey.getValue());

        assertEquals("file:/run/secrets/privateKey.pem", TokenUtils.getPrivateKeyLocation());
        assertEquals("file:/run/secrets/publicKey.pem", TokenUtils.getPublicKeyLocation());
    }

    private static ConfigValue configValue(String name, String value, String sourceName) {
        ConfigValue.ConfigValueBuilder builder = ConfigValue.builder().withName(name);
        if (value != null) {
            builder.withValue(value).withRawValue(value);
        }
        if (sourceName != null) {
            builder.withConfigSourceName(sourceName).withConfigSourceOrdinal(300);
        }
        return builder.build();
    }
}
