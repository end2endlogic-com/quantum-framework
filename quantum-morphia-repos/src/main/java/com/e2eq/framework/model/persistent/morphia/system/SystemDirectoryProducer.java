package com.e2eq.framework.model.persistent.morphia.system;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Selects the {@link SystemDirectory} implementation from configuration
 * (CONTROL_PLANE_SPLIT_DESIGN.md §4).
 *
 * <pre>
 *   quantum.system.directory.mode=local   # default; system realm DB via repos
 *   quantum.system.directory.mode=remote  # Phase C; control-plane HTTP client
 * </pre>
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

    @ConfigProperty(name = "quantum.system.directory.mode", defaultValue = MODE_LOCAL)
    String mode;

    @Produces
    @ApplicationScoped
    public SystemDirectory systemDirectory() {
        String normalized = mode == null ? MODE_LOCAL : mode.trim().toLowerCase();
        switch (normalized) {
            case MODE_LOCAL:
                return new LocalSystemDirectory(realmRepo, credentialRepo, envConfigUtils);
            case MODE_REMOTE:
                throw new IllegalStateException(
                    "quantum.system.directory.mode=remote is not available yet: the "
                    + "control-plane client arrives in Phase C of the control-plane split. "
                    + "Use mode=local (default).");
            default:
                throw new IllegalStateException(
                    "Unknown quantum.system.directory.mode '" + mode + "'; expected 'local' or 'remote'.");
        }
    }
}
