package com.e2eq.framework.service.startup;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.service.seed.PendingSeedsStartupLogger;
import com.e2eq.framework.service.seed.SeedStartupRunner;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class FrameworkStartupCoordinator {

    @Inject
    MigrationService migrationService;

    @Inject
    SeedStartupRunner seedStartupRunner;

    @Inject
    PendingSeedsStartupLogger pendingSeedsStartupLogger;

    void onStart(@Observes StartupEvent event) {
        migrationService.initializeStartupRealms();
        seedStartupRunner.onStart();
        pendingSeedsStartupLogger.onStart();
    }
}
