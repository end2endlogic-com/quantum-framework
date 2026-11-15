package com.e2eq.framework.system.local;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.remote.RemoteSystemDirectory;
import com.e2eq.framework.system.remote.SplitSystemDirectory;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Optional;

/**
 * Selects the {@link SystemDirectory} implementation from the deployment mode
 * (CONTROL_PLANE_SPLIT_DESIGN.md §4). Lives in the {@code quantum-system}
 * control-plane module; an application/framework that depends on this jar
 * gets the producer via Jandex bean discovery.
 *
 * {@code quantum.directory.mode} ({@link QuantumModeConfig}), when set, decides:
 * local → the system realm DB via repos; remote → the directory owner over the
 * control-plane client, or {@link SplitSystemDirectory} when this deployment
 * still owns a system realm (embedded). When it is unset the older routing
 * applies unchanged: {@code quantum.system.directory.mode}, else
 * {@code quantum.mode} (embedded → local, remote → control-plane client).
 *
 * Remote mode fails loud until the control-plane client exists — no silent
 * fallback to local.
 */
@ApplicationScoped
public class SystemDirectoryProducer {

    @Inject RealmRepo realmRepo;
    @Inject CredentialRepo credentialRepo;
    @Inject EnvConfigUtils envConfigUtils;
    @Inject QuantumModeConfig quantumModeConfig;
    @Inject JsonWebToken jwt;

    @Produces
    @ApplicationScoped
    public SystemDirectory systemDirectory() {
        Optional<String> explicit = quantumModeConfig.directoryMode();
        if (explicit.isPresent()) {
            // quantum.directory.mode covers the catalog and memberships together.
            if (QuantumModeConfig.DIRECTORY_LOCAL.equals(explicit.get())) {
                return local();
            }
            // A deployment that owns a system realm keeps it for its own records and reads
            // only the realm catalog from the directory owner.
            return quantumModeConfig.isRemote() ? remote() : new SplitSystemDirectory(local(), remote());
        }
        // Unset: the older routing, as QuantumModeConfig (the one parser of the mode keys) reads it.
        return quantumModeConfig.catalogRemote() ? remote() : local();
    }

    private SystemDirectory local() {
        return new LocalSystemDirectory(realmRepo, credentialRepo, envConfigUtils);
    }

    /**
     * Phase C: realm-catalog operations go to the directory owner; identity and
     * system-realm accessors fail loud by design (JWKS validates identity; no
     * local system realm exists in tier 2).
     */
    private SystemDirectory remote() {
        return new RemoteSystemDirectory(
            quantumModeConfig.directoryBaseUrl().orElseThrow(() ->
                new IllegalStateException("quantum.directory.base-url is required for a remote directory")),
            () -> {
                try {
                    String inboundToken = jwt.getRawToken();
                    if (inboundToken != null && !inboundToken.isBlank()) {
                        return Optional.of(inboundToken);
                    }
                } catch (RuntimeException ignored) {
                    // No active request context (for example, startup). A configured service
                    // token is still allowed; otherwise the remote call fails closed.
                }
                return quantumModeConfig.directoryToken();
            });
    }
}
