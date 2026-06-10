package com.e2eq.framework.service.startup;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.bootstrap.runtime.BootstrapPackStartupRunner;
import com.e2eq.framework.bootstrap.runtime.PendingBootstrapPacksStartupLogger;
import com.e2eq.framework.service.seed.PendingSeedsStartupLogger;
import com.e2eq.framework.service.seed.SeedStartupRunner;
import com.e2eq.framework.system.config.QuantumModeConfig;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Drives the framework's startup sequence. Mode-aware per the control-plane
 * split (CONTROL_PLANE_SPLIT_DESIGN.md; wp3 tier 2): in embedded mode
 * (default) this is today's behavior — the app owns its system realm. In
 * remote mode the control plane owns the system realm, so system-realm
 * migrations and baseline identity are skipped here while app-realm
 * migrations, seeds, and bootstrap packs still run locally (the runners
 * themselves exclude the system realm from their realm lists in remote mode).
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
    BaselineIdentityStartupService baselineIdentityStartupService;

    @Inject
    BootstrapPackStartupRunner bootstrapPackStartupRunner;

    @Inject
    PendingBootstrapPacksStartupLogger pendingBootstrapPacksStartupLogger;

    void onStart(@Observes StartupEvent event) {
        boolean embedded = quantumModeConfig.isEmbedded();
        if (!embedded) {
            Log.infof("FrameworkStartupCoordinator: quantum.mode=remote — the control plane owns the "
                + "system realm; skipping system-realm migrations and baseline identity, "
                + "app-realm startup work still runs locally (control plane: %s)",
                quantumModeConfig.systemServiceBaseUrl().orElse("<unset>"));
        }

        migrationService.initializeStartupRealms(embedded);
        if (embedded) {
            baselineIdentityStartupService.onStart();
        }
        seedStartupRunner.onStart();
        bootstrapPackStartupRunner.onStart();
        pendingSeedsStartupLogger.onStart();
        pendingBootstrapPacksStartupLogger.onStart();
    }
}
