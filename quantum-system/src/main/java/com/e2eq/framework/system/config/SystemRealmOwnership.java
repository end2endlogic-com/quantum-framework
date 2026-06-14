package com.e2eq.framework.system.config;

import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collection;

/**
 * Answers "does this deployment own the system realm's lifecycle?" for the
 * startup path (CONTROL_PLANE_SPLIT_DESIGN.md; wp3 tier 2).
 *
 * Embedded mode (default): yes — system-realm migrations, baseline identity,
 * seeds, and bootstrap packs run locally, exactly as today.
 * Remote mode: no — the control plane owns the system realm; local startup
 * work must exclude it while app realms proceed normally.
 *
 * Startup runners consult this bean instead of reading {@code quantum.mode}
 * themselves, so the ownership semantics stay in the control-plane module.
 */
@ApplicationScoped
public class SystemRealmOwnership {

    @Inject
    QuantumModeConfig quantumModeConfig;

    @Inject
    EnvConfigUtils envConfigUtils;

    /** Non-CDI construction (unit tests, embedding). */
    public static SystemRealmOwnership of(QuantumModeConfig quantumModeConfig, EnvConfigUtils envConfigUtils) {
        SystemRealmOwnership ownership = new SystemRealmOwnership();
        ownership.quantumModeConfig = quantumModeConfig;
        ownership.envConfigUtils = envConfigUtils;
        return ownership;
    }

    /** True when this deployment owns the system realm (embedded mode). */
    public boolean ownedLocally() {
        return quantumModeConfig.isEmbedded();
    }

    /**
     * Remove the system realm from a startup realm list when this deployment
     * does not own it. Mutates {@code realms}; logs when an exclusion happens.
     *
     * @param realms    mutable realm list a startup runner resolved
     * @param subsystem caller name for the log line (e.g. "SeedStartupRunner")
     * @return true if the system realm was present and removed
     */
    public boolean excludeSystemRealmIfNotOwned(Collection<String> realms, String subsystem) {
        if (ownedLocally()) {
            return false;
        }
        String systemRealm = envConfigUtils.getSystemRealm();
        boolean removed = realms.remove(systemRealm);
        if (removed) {
            Log.infof("%s: quantum.mode=remote — system realm %s excluded from startup work (the control plane owns it)",
                subsystem, systemRealm);
        }
        return removed;
    }
}
