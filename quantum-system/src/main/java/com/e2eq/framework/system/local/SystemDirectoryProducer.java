package com.e2eq.framework.system.local;

import com.e2eq.framework.api.system.QuantumMode;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Selects the {@link SystemDirectory} implementation from the deployment mode
 * (CONTROL_PLANE_SPLIT_DESIGN.md §4). Lives in the {@code quantum-system}
 * control-plane module; an application/framework that depends on this jar
 * gets the producer via Jandex bean discovery.
 *
 * The directory follows {@code quantum.mode} ({@link QuantumModeConfig}):
 * embedded → local (system realm DB via repos), remote → control-plane HTTP
 * client (Phase C). {@code quantum.system.directory.mode} remains available as
 * an explicit override for the directory alone — useful in migration windows
 * where an app runs remote but still resolves the directory locally.
 *
 * Remote mode fails loud until the control-plane client exists — no silent
 * fallback to local.
 */
@ApplicationScoped
public class SystemDirectoryProducer {

    static final String MODE_LOCAL = "local";
    static final String MODE_REMOTE = "remote";

    @Inject RealmRepo realmRepo;
    @Inject CredentialRepo credentialRepo;
    @Inject EnvConfigUtils envConfigUtils;
    @Inject QuantumModeConfig quantumModeConfig;

    @ConfigProperty(name = "quantum.system.directory.mode")
    Optional<String> directoryModeOverride;

    /** Optional bearer for control-plane calls (service identity). */
    @ConfigProperty(name = "quantum.system-service.token")
    Optional<String> serviceToken;

    @Produces
    @ApplicationScoped
    public SystemDirectory systemDirectory() {
        String effective = directoryModeOverride
            .map(v -> v.trim().toLowerCase())
            .orElseGet(() -> quantumModeConfig.mode() == QuantumMode.REMOTE ? MODE_REMOTE : MODE_LOCAL);
        switch (effective) {
            case MODE_LOCAL:
                return new LocalSystemDirectory(realmRepo, credentialRepo, envConfigUtils);
            case MODE_REMOTE:
                // Phase C: realm-catalog operations go to the control plane;
                // identity/system-realm accessors fail loud by design (JWKS
                // validates identity; no local system realm exists in tier 2).
                return new com.e2eq.framework.system.remote.RemoteSystemDirectory(
                    quantumModeConfig.systemServiceBaseUrl().orElseThrow(() ->
                        new IllegalStateException("quantum.system-service.base-url is required for remote SystemDirectory")),
                    serviceToken);
            default:
                throw new IllegalStateException(
                    "Unknown quantum.system.directory.mode '" + directoryModeOverride.orElse(null)
                    + "'; expected 'local' or 'remote'.");
        }
    }
}
