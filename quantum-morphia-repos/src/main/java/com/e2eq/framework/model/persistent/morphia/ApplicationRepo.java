package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Global application registry. Like credential, policy, and realm records,
 * applications are a system-plane resource: the {@code application} collection
 * exists ONLY in the configured system realm (quantum-auth) database. Pinning
 * the security-context realm here makes every inherited repo path — including
 * the generic {@code ApplicationResource} CRUD — read and write that single
 * registry regardless of the caller's request realm or X-Realm override.
 */
@ApplicationScoped
public class ApplicationRepo extends MorphiaRepo<Application> {

    @Override
    public String getSecurityContextRealmId() {
        return envConfigUtils.getSystemRealm();
    }

    /**
     * Rule-free lookup for bootstrap/provisioning paths that run before or
     * outside a principal security context (mirrors the credential/realm repos).
     */
    public java.util.Optional<Application> findByRefNameWithIgnoreRules(String refName) {
        if (refName == null || refName.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
            morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
                .find(Application.class)
                .filter(dev.morphia.query.filters.Filters.eq("refName", refName.trim()))
                .first());
    }

    /**
     * Idempotently registers an application in the global registry (system
     * realm). Provisioning uses this so creating a realm for a new application
     * also creates the application record — the registry is derived from use,
     * never hand-maintained.
     */
    public Application ensureRegistered(String refName,
                                        com.e2eq.framework.model.persistent.base.DataDomain dataDomain) {
        return findByRefNameWithIgnoreRules(refName).orElseGet(() -> {
            Application application = new Application();
            application.setRefName(refName.trim());
            application.setDisplayName(refName.trim());
            application.setDataDomain(dataDomain);
            return save(getSecurityContextRealmId(), application);
        });
    }
}
