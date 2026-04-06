package com.e2eq.framework.model.auth.provider.jwtToken;

import io.quarkus.logging.Log;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.List;

/**
 * CDI bean that configures {@link TokenUtils} key file locations at startup
 * from application.properties. If the properties are not set, the defaults
 * (classpath:privateKey.pem / classpath:publicKey.pem) are used.
 */
@ApplicationScoped
@Startup
public class TokenUtilsConfigurer {

    private static final String PRIVATE_KEY_CONFIG = "quantum.jwt.private-key-location";
    private static final String PUBLIC_KEY_CONFIG = "quantum.jwt.public-key-location";

    @Inject
    JwtKeyResolver jwtKeyResolver;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        List<String> activeProfiles = ConfigUtils.getProfiles();
        SmallRyeConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class);
        ConfigValue privateKeyConfig = config.getConfigValue(PRIVATE_KEY_CONFIG);
        ConfigValue publicKeyConfig = config.getConfigValue(PUBLIC_KEY_CONFIG);
        String privLoc = privateKeyConfig.getValue();
        String pubLoc = publicKeyConfig.getValue();

        validateConfiguredKeyLocations(activeProfiles, privateKeyConfig, publicKeyConfig);
        TokenUtils.configureKeyResolver(jwtKeyResolver);

        if (privLoc != null || pubLoc != null) {
            Log.infof("Configuring TokenUtils key locations from %s/%s for profiles %s",
                    describeConfigSource(privateKeyConfig, privLoc),
                    describeConfigSource(publicKeyConfig, pubLoc),
                    activeProfiles);
            TokenUtils.configure(privLoc, pubLoc);
        } else {
            Log.info("TokenUtils using default classpath key locations (privateKey.pem / publicKey.pem)");
        }
    }

    static void validateConfiguredKeyLocations(List<String> activeProfiles, ConfigValue privateKeyConfig, ConfigValue publicKeyConfig) {
        if (isNonProduction(activeProfiles)) {
            return;
        }

        requireExplicitProductionConfig(PRIVATE_KEY_CONFIG, privateKeyConfig);
        requireExplicitProductionConfig(PUBLIC_KEY_CONFIG, publicKeyConfig);
    }

    private static boolean isNonProduction(List<String> activeProfiles) {
        if (activeProfiles != null) {
            for (String profile : activeProfiles) {
                if (LaunchMode.DEV_PROFILE.equals(profile) || LaunchMode.TEST_PROFILE.equals(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void requireExplicitProductionConfig(String configName, ConfigValue configValue) {
        if (!isExplicitlyConfigured(configValue)) {
            throw new IllegalStateException(String.format(
                    "Production JWT startup requires %s to be explicitly configured; default JWT key fallback is not allowed", configName));
        }
    }

    private static boolean isExplicitlyConfigured(ConfigValue configValue) {
        return configValue != null
                && configValue.getValue() != null
                && !configValue.getValue().isBlank()
                && configValue.getSourceName() != null
                && !configValue.getSourceName().isBlank();
    }

    private static String describeConfigSource(ConfigValue configValue, String location) {
        if (configValue != null && configValue.getSourceName() != null && !configValue.getSourceName().isBlank()) {
            if (configValue.getSourceName().toLowerCase().contains("env")) {
                return "environment";
            }
            if (configValue.getSourceName().toLowerCase().contains("sys")) {
                return "system-property";
            }
            return "config:" + configValue.getSourceName();
        }
        return describeLocationSource(location);
    }

    private static String describeLocationSource(String location) {
        if (location == null || location.isBlank()) {
            return "default-classpath";
        }
        String normalized = location.trim();
        if (normalized.startsWith("file:")) {
            return "filesystem";
        }
        if (normalized.startsWith("classpath:")) {
            return "classpath";
        }
        return "classpath-implicit";
    }
}
