package com.e2eq.framework.service.startup;

import com.e2eq.framework.bootstrap.runtime.BootstrapPackStartupRunner;
import com.e2eq.framework.bootstrap.runtime.PendingBootstrapPacksStartupLogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The full aggregate's contribution to {@link FrameworkStartupCoordinator}: baseline
 * identity when this process owns its system realm, and bootstrap packs. Together with
 * the coordinator this keeps the historical order — migrate, baseline identity (embedded
 * only), seeds, bootstrap packs, pending-seeds report, pending-bootstrap report.
 */
@ApplicationScoped
public class AggregateStartupHooks implements FrameworkStartupHook {

    @Inject
    BaselineIdentityStartupService baselineIdentityStartupService;

    @Inject
    BootstrapPackStartupRunner bootstrapPackStartupRunner;

    @Inject
    PendingBootstrapPacksStartupLogger pendingBootstrapPacksStartupLogger;

    @Override
    public void afterMigration(boolean embedded) {
        if (embedded) {
            baselineIdentityStartupService.onStart();
        }
    }

    @Override
    public void afterSeeds(boolean embedded) {
        bootstrapPackStartupRunner.onStart();
    }

    @Override
    public void afterStartupReport(boolean embedded) {
        pendingBootstrapPacksStartupLogger.onStart();
    }
}
