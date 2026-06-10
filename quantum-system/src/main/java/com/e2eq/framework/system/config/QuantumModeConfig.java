package com.e2eq.framework.system.config;

import com.e2eq.framework.api.system.QuantumMode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Single source of the deployment mode (`quantum.mode`, default embedded) and
 * the control-plane endpoint for remote mode (`quantum.system-service.base-url`).
 *
 * This is THE app-facing knob of the control-plane split (wp3 §1): existing
 * apps change nothing and stay embedded; a tier-2 app sets
 * {@code quantum.mode=remote} plus the base URL. Everything mode-dependent —
 * SystemDirectory selection, system-realm startup work — derives from this
 * bean rather than reading the property again.
 *
 * Fail-loud: an unknown mode value aborts startup; remote mode without a
 * base URL aborts startup.
 */
@ApplicationScoped
public class QuantumModeConfig {

    @ConfigProperty(name = "quantum.mode", defaultValue = "embedded")
    String configuredMode;

    @ConfigProperty(name = "quantum.system-service.base-url")
    Optional<String> systemServiceBaseUrl;

    private QuantumMode mode;

    /**
     * Non-CDI construction (unit tests, embedding): validates exactly like the
     * container path.
     */
    public static QuantumModeConfig of(String configuredMode, Optional<String> systemServiceBaseUrl) {
        QuantumModeConfig config = new QuantumModeConfig();
        config.configuredMode = configuredMode;
        config.systemServiceBaseUrl = systemServiceBaseUrl;
        config.init();
        return config;
    }

    @PostConstruct
    void init() {
        mode = QuantumMode.fromConfig(configuredMode);
        if (mode == QuantumMode.REMOTE && systemServiceBaseUrl.isEmpty()) {
            throw new IllegalStateException(
                "quantum.mode=remote requires quantum.system-service.base-url to be set "
                + "(the control-plane service endpoint).");
        }
    }

    public QuantumMode mode() {
        return mode;
    }

    public boolean isEmbedded() {
        return mode == QuantumMode.EMBEDDED;
    }

    public boolean isRemote() {
        return mode == QuantumMode.REMOTE;
    }

    /** Control-plane endpoint; present when {@link #isRemote()}. */
    public Optional<String> systemServiceBaseUrl() {
        return systemServiceBaseUrl;
    }
}
