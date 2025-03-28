package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
@Startup
public class MigrationStartupRunner {
    @ConfigProperty(name = "quantum.database.migration.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    MigrationService migrationService;

    @Inject
    SecurityUtils securityUtils;

    @Inject
    RuleContext ruleContext;
    @Inject
    TestUtils testUtils;

    @PostConstruct
    public void runMigration() {
        if (enabled) {
            Log.warn("-----!!!  Migrations ENABLED !!!!-----");
            String[] roles = {"admin", "user"};
            ruleContext.ensureDefaultRules();
            PrincipalContext pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
            ResourceContext rContext = testUtils.getResourceContext(testUtils.getArea(), "userProfile", "update");
            testUtils.initDefaultRules(ruleContext, "SYSTEM", "MIGRATION", testUtils.getTestUserId());
            try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
                migrationService.runAllUnRunMigrations(securityUtils.getTestRealm());
                migrationService.runAllUnRunMigrations(securityUtils.getSystemRealm());
                migrationService.runAllUnRunMigrations(securityUtils.getDefaultRealm());
            }
        }
    }
}
