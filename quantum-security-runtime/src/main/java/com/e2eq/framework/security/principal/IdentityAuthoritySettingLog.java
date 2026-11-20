package com.e2eq.framework.security.principal;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;

/**
 * Logs at INFO on startup whether this service is the identity authority
 * ({@code quantum.auth.identity-authority}, read by {@code SecurityFilter}) and which configuration
 * source set it. The setting is deployment-controlled (properties, profiles, environment, system
 * properties), never reachable by a tenant, so the log is how an operator confirms it.
 */
@ApplicationScoped
public class IdentityAuthoritySettingLog {

    static final String PROPERTY = "quantum.auth.identity-authority";

    void onStart(@Observes StartupEvent event) {
        Log.info(message(ConfigProvider.getConfig().getConfigValue(PROPERTY)));
    }

    static String message(ConfigValue value) {
        String raw = value == null ? null : value.getValue();
        boolean on = raw != null && Boolean.parseBoolean(raw.trim());
        String source = value == null || value.getSourceName() == null ? "default" : value.getSourceName();
        return String.format("%s=%s (source: %s): %s", PROPERTY, on, source,
                on ? "this service resolves every service token v2 for any application"
                   : "service tokens v2 are admitted only by a receiver that names its application");
    }
}
