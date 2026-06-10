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
                throw new IllegalStateException(
                    "SystemDirectory remote mode is not available yet: the control-plane "
                    + "client arrives in Phase C of the control-plane split. Use "
                    + "quantum.mode=embedded (default) or override "
                    + "quantum.system.directory.mode=local during migration.");
            default:
                throw new IllegalStateException(
                    "Unknown quantum.system.directory.mode '" + directoryModeOverride.orElse(null)
                    + "'; expected 'local' or 'remote'.");
        }
    }
}
