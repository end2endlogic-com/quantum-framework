package com.e2eq.framework.system.config;

import com.e2eq.framework.api.system.QuantumMode;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.Optional;

/**
 * Single source of the deployment mode (`quantum.mode`, default embedded) and
 * of where the directory (realm catalog and memberships) is read from.
 *
 * This is THE app-facing knob of the control-plane split (wp3 §1): existing
 * apps change nothing and stay embedded; a tier-2 app sets
 * {@code quantum.mode=remote} plus the base URL. Everything mode-dependent —
 * SystemDirectory selection, system-realm startup work — derives from this
 * bean rather than reading the property again.
 *
 * <p><b>Directory routing</b> ({@code quantum.directory.mode}, local | remote).
 * {@code quantum.mode} says whether this deployment owns a system realm.
 * {@code quantum.directory.mode} says where the realm catalog and the
 * memberships live, and covers both together. Centralized Auth is the
 * reference architecture: one {@code quantum-auth-service} per environment owns
 * the directory, and every other service reads it remotely, including a
 * service that keeps its own system realm for its own records (embedded mode
 * with a remote directory).</p>
 *
 * <p>When {@code quantum.directory.mode} is unset, behaviour is exactly what it
 * was before the setting existed: memberships follow {@code quantum.mode}, and
 * the realm catalog follows {@code quantum.system.directory.mode} or else
 * {@code quantum.mode}. Existing deployments therefore change only when they
 * set the new key.</p>
 *
 * <p>The directory endpoint is {@code quantum.directory.base-url}. The older
 * {@code quantum.system-service.base-url} and {@code quantum.system-service.token}
 * are still read as fallbacks for one release.</p>
 *
 * Fail-loud: an unknown mode value aborts startup; a remote directory without a
 * base URL aborts startup.
 */
@ApplicationScoped
public class QuantumModeConfig {

    public static final String DIRECTORY_LOCAL = "local";
    public static final String DIRECTORY_REMOTE = "remote";

    @ConfigProperty(name = "quantum.mode", defaultValue = "embedded")
    String configuredMode;

    @ConfigProperty(name = "quantum.system-service.base-url")
    Optional<String> systemServiceBaseUrl;

    @ConfigProperty(name = "quantum.directory.mode")
    Optional<String> configuredDirectoryMode = Optional.empty();

    @ConfigProperty(name = "quantum.directory.base-url")
    Optional<String> configuredDirectoryBaseUrl = Optional.empty();

    @ConfigProperty(name = "quantum.directory.token")
    Optional<String> configuredDirectoryToken = Optional.empty();

    @ConfigProperty(name = "quantum.system-service.token")
    Optional<String> systemServiceToken = Optional.empty();

    private QuantumMode mode;
    private Optional<String> directoryMode = Optional.empty();

    /**
     * Non-CDI construction (unit tests, embedding): validates exactly like the
     * container path.
     */
    public static QuantumModeConfig of(String configuredMode, Optional<String> systemServiceBaseUrl) {
        return of(configuredMode, systemServiceBaseUrl, Optional.empty(), Optional.empty());
    }

    /** Non-CDI construction with explicit directory routing. */
    public static QuantumModeConfig of(String configuredMode, Optional<String> systemServiceBaseUrl,
                                       Optional<String> directoryMode, Optional<String> directoryBaseUrl) {
        return of(configuredMode, systemServiceBaseUrl, directoryMode, directoryBaseUrl, Optional.empty());
    }

    /** Non-CDI construction with explicit directory routing and a service bearer. */
    public static QuantumModeConfig of(String configuredMode, Optional<String> systemServiceBaseUrl,
                                       Optional<String> directoryMode, Optional<String> directoryBaseUrl,
                                       Optional<String> directoryToken) {
        QuantumModeConfig config = new QuantumModeConfig();
        config.configuredMode = configuredMode;
        config.systemServiceBaseUrl = systemServiceBaseUrl;
        config.configuredDirectoryMode = directoryMode;
        config.configuredDirectoryBaseUrl = directoryBaseUrl;
        config.configuredDirectoryToken = directoryToken;
        config.init();
        return config;
    }

    @PostConstruct
    void init() {
        mode = QuantumMode.fromConfig(configuredMode);
        directoryMode = present(configuredDirectoryMode).map(value -> value.toLowerCase(Locale.ROOT));
        directoryMode.ifPresent(value -> {
            if (!DIRECTORY_LOCAL.equals(value) && !DIRECTORY_REMOTE.equals(value)) {
                throw new IllegalStateException(
                    "Unknown quantum.directory.mode '" + value + "'; expected 'local' or 'remote'.");
            }
        });
        if (mode == QuantumMode.REMOTE && directoryBaseUrl().isEmpty()) {
            throw new IllegalStateException(
                "quantum.mode=remote requires quantum.directory.base-url (or the older "
                + "quantum.system-service.base-url) to be set (the control-plane service endpoint).");
        }
        if (membershipsRemote() && directoryBaseUrl().isEmpty()) {
            throw new IllegalStateException(
                "quantum.directory.mode=remote requires quantum.directory.base-url "
                + "(the quantum-auth-service that owns the directory).");
        }
        if (directoryMode.isPresent() && present(configuredDirectoryBaseUrl).isEmpty()
                && present(systemServiceBaseUrl).isPresent() && membershipsRemote()) {
            Log.warn("quantum.system-service.base-url is deprecated for the directory; "
                + "set quantum.directory.base-url instead.");
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

    /** Control-plane endpoint as configured under its original key. */
    public Optional<String> systemServiceBaseUrl() {
        return systemServiceBaseUrl;
    }

    /**
     * The explicit {@code quantum.directory.mode} (local | remote), when set.
     * Empty means the pre-existing routing applies.
     */
    public Optional<String> directoryMode() {
        return directoryMode;
    }

    /** True when realm memberships are read and written through the remote directory. */
    public boolean membershipsRemote() {
        return directoryMode.map(DIRECTORY_REMOTE::equals).orElseGet(this::isRemote);
    }

    /** The directory endpoint: {@code quantum.directory.base-url}, else the older key. */
    public Optional<String> directoryBaseUrl() {
        return present(configuredDirectoryBaseUrl).or(() -> present(systemServiceBaseUrl));
    }

    /** Service bearer for directory calls made outside a request: the new key, else the older one. */
    public Optional<String> directoryToken() {
        return present(configuredDirectoryToken).or(() -> present(systemServiceToken));
    }

    private static Optional<String> present(Optional<String> value) {
        return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
    }
}
