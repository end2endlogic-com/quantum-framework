package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Admits a realm name taken from caller input (an {@code X-Realm} header, or a path or query
 * parameter naming a realm) before anything opens a datastore for it. A realm refName is a
 * database name, and opening a datastore builds its indexes, which creates the database: a name no
 * one registered must never get that far.
 *
 * <p>Admitted: the configured system and default realms, and a realm the directory registers
 * ({@link RealmRefNames#registered}, cached). That a database of the name already exists is not
 * enough. Refused 403 {@code REALM_NOT_AUTHORIZED}: a name that is not a refName's shape, a
 * reserved database name ({@code admin}, {@code local}, {@code config}, the coordination
 * database), or an unregistered name. A directory that cannot answer is 503
 * {@code PRINCIPAL_RESOLUTION_UNAVAILABLE}.</p>
 *
 * <p>Only request input is gated here. Startup migrations, seeders and provisioning open the
 * realms they are configured with or create, unchanged.</p>
 */
@ApplicationScoped
public class RequestRealmAdmission {

    /** MongoDB's own databases: never a realm. */
    static final Set<String> RESERVED_DATABASES = Set.of("admin", "local", "config");

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    Instance<SystemDirectory> systemDirectory;

    @ConfigProperty(name = "quantum.database.coordination-database", defaultValue = "quantum-coordination-shared")
    String coordinationDatabase = "quantum-coordination-shared";

    /** True when {@code name} (trimmed, ignoring case) is a database that is never a realm. */
    public boolean reserved(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return RESERVED_DATABASES.contains(lower)
                || (coordinationDatabase != null && lower.equals(coordinationDatabase.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * The realm {@code requested} names, as its registered refName.
     *
     * @throws RemotePrincipalResolutionException 403 {@code REALM_NOT_AUTHORIZED} when it names no
     *         admitted realm; 503 {@code PRINCIPAL_RESOLUTION_UNAVAILABLE} when the directory cannot answer
     */
    public String admit(String requested) {
        return admitted(requested).orElseThrow(() -> RemotePrincipalResolutionException.of(
                PrincipalResolutionError.REALM_NOT_AUTHORIZED, "The requested realm is not a registered realm"));
    }

    /**
     * The registered refName {@code requested} names, or empty when it names no admitted realm.
     *
     * @throws RemotePrincipalResolutionException 503 when the directory cannot answer
     */
    public Optional<String> admitted(String requested) {
        if (requested == null || !RealmRefNames.isRefNameShape(requested)) {
            return Optional.empty();
        }
        String trimmed = requested.trim();
        if (reserved(trimmed)) {
            return Optional.empty();
        }
        if (configured(trimmed)) {
            return Optional.of(trimmed);
        }
        SystemDirectory directory = systemDirectory != null && systemDirectory.isResolvable() ? systemDirectory.get() : null;
        return RealmRefNames.registered(directory, trimmed);
    }

    private boolean configured(String trimmed) {
        return envConfigUtils != null
                && (trimmed.equals(envConfigUtils.getSystemRealm()) || trimmed.equals(envConfigUtils.getDefaultRealm()));
    }
}
