package com.e2eq.framework.system.catalog;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Control-plane realm catalog: the directory of tenants → realms that both
 * planes consult (system plane read/write, data plane read-only / cached from
 * JWT). It sits over {@link SystemDirectory} so callers — tenant provisioning,
 * and later the {@code quantum-system-rest} admin resources — depend on a
 * stable catalog API rather than the lower-level directory seam.
 *
 * Design: CONTROL_PLANE_SPLIT_DESIGN.md §3–§5; wp1-platform-readiness.md (B1).
 *
 * Keyed by the configured deployment system realm (per-app system realms such
 * as {@code system-psa-com} are first-class): this service never assumes a
 * single global {@code system-com}; it resolves through
 * {@link SystemDirectory#systemRealmId()}.
 *
 * No JAX-RS here — this jar carries no REST resources by design.
 */
@ApplicationScoped
public class RealmCatalogService {

    @Inject
    SystemDirectory systemDirectory;

    /** The deployment's configured system realm reference. */
    public String systemRealmId() {
        return systemDirectory.systemRealmId();
    }

    /** Look up a realm catalog entry by its tenant email domain. */
    public Optional<Realm> findByEmailDomain(String emailDomain) {
        if (emailDomain == null || emailDomain.isBlank()) {
            return Optional.empty();
        }
        return systemDirectory.findRealmByEmailDomain(emailDomain.trim());
    }

    /** Look up a realm catalog entry by its reference name. */
    public Optional<Realm> findByRefName(String refName) {
        if (refName == null || refName.isBlank()) {
            return Optional.empty();
        }
        return systemDirectory.findRealmByRefName(refName.trim());
    }

    /** True if a realm with the given reference name exists in the catalog. */
    public boolean exists(String refName) {
        return findByRefName(refName).isPresent();
    }

    /**
     * Create or update a realm catalog entry. Idempotent at the catalog level:
     * callers (e.g. tenant provisioning) decide create-vs-update semantics; this
     * method persists the desired state via the directory.
     */
    public Realm register(Realm realm) {
        if (realm == null) {
            throw new IllegalArgumentException("realm must not be null");
        }
        if (realm.getRefName() == null || realm.getRefName().isBlank()) {
            throw new IllegalArgumentException("realm.refName must be set before registration");
        }
        Realm saved = systemDirectory.registerRealm(realm);
        Log.infof("RealmCatalogService: registered realm %s in system realm %s",
                saved.getRefName(), systemRealmId());
        return saved;
    }
}
