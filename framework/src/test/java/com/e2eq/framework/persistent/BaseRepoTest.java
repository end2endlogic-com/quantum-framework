package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.exceptions.DatabaseMigrationException;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTest
public class BaseRepoTest {

    @Inject
    protected TestUtils testUtils;

    @Inject
    protected RuleContext ruleContext;

    @Inject
    MigrationService migrationService;

    protected String[] roles = {"admin", "user"};
    protected PrincipalContext pContext;
    protected ResourceContext rContext;

    @PostConstruct
    void init() {
        ruleContext.ensureDefaultRules();
        pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        rContext = testUtils.getResourceContext(testUtils.getArea(), "userProfile", "update");
        testUtils.initDefaultRules(ruleContext, "security","userProfile", testUtils.getTestUserId());
        // check if testDatabase has been migrated if not migrate it
        try {
            migrationService.isMigrationRequired();
        } catch (DatabaseMigrationException ex ) {
            Log.info("==== Attempting to run migration scripts ======");

            // Use MultiEmitter to print emitted items to System.out
            Multi.createFrom().emitter(emitter -> {
                migrationService.runAllUnRunMigrations(testUtils.getTestRealm(), emitter);
            }).subscribe().with(
               item -> System.out.println(item),
               failure -> System.err.println("Failed: " + failure.getMessage()),
                  () -> Log.error("Migration Complete"));


        }

    }


    /* @BeforeEach
    protected void setUp() {


    }

    @AfterEach
    void tearDown() {

    } */
}
