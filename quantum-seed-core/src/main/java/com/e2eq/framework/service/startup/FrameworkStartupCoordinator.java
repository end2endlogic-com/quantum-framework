package com.e2eq.framework.service.startup;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.service.seed.PendingSeedsStartupLogger;
import com.e2eq.framework.service.seed.SeedStartupRunner;
import com.e2eq.framework.system.config.QuantumModeConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;

/**
 * Drives the framework's startup sequence from configuration.
 *
 * <p>Lives in {@code quantum-seed-core} — the module every persisting slice carries — so
 * a tenant plane assembled from {@code quantum-morphia-repos} + {@code quantum-seed-core}
 * gets the same behaviour as the full aggregate without an application bean:
 * {@code quantum.database.migration.enabled} / {@code quantum.migration.apply.realms}
 * drive migrations and {@code quantum.seed-pack.apply.on-startup} /
 * {@code quantum.seed-pack.apply.realms} drive seeds.</p>
 *
 * <p>Mode-aware per the control-plane split (CONTROL_PLANE_SPLIT_DESIGN.md; wp3 tier 2):
 * in embedded mode (default) the app owns its system realm. In remote mode the control
 * plane owns the system realm, so system-realm migrations are skipped here while
 * app-realm migrations and seeds still run locally (the runners themselves exclude the
 * system realm from their realm lists in remote mode). Work that needs modules this
 * module does not depend on — baseline identity, bootstrap packs — is contributed
 * through {@link FrameworkStartupHook}.</p>
 */
@ApplicationScoped
public class FrameworkStartupCoordinator {

    @Inject
    QuantumModeConfig quantumModeConfig;

    @Inject
    MigrationService migrationService;

    @Inject
    SeedStartupRunner seedStartupRunner;

    @Inject
    PendingSeedsStartupLogger pendingSeedsStartupLogger;

    @Inject
    Instance<FrameworkStartupHook> hooks;

    void onStart(@Observes StartupEvent event) {
        boolean embedded = quantumModeConfig.isEmbedded();
        if (!embedded) {
            Log.infof("FrameworkStartupCoordinator: quantum.mode=remote — the control plane owns the "
                + "system realm; skipping system-realm migrations and baseline identity, "
                + "app-realm startup work still runs locally (control plane: %s)",
                quantumModeConfig.systemServiceBaseUrl().orElse("<unset>"));
        }
        List<FrameworkStartupHook> ordered = hooks.stream()
            .sorted(Comparator.comparingInt(FrameworkStartupHook::order))
            .toList();
        if (ordered.isEmpty()) {
            Log.info("FrameworkStartupCoordinator: no startup hooks registered (migrations and seeds only)");
        }

        migrationService.initializeStartupRealms(embedded);
        ordered.forEach(hook -> hook.afterMigration(embedded));
        seedStartupRunner.onStart();
        ordered.forEach(hook -> hook.afterSeeds(embedded));
        pendingSeedsStartupLogger.onStart();
        ordered.forEach(hook -> hook.afterStartupReport(embedded));
    }
}
