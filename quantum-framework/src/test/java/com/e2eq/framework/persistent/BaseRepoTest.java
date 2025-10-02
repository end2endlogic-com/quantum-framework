package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

@QuarkusTest
@io.quarkus.test.common.QuarkusTestResource(com.e2eq.framework.test.MongoDbInitResource.class)
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
        ruleContext.initDefaultRules( "security","userProfile", testUtils.getTestUserId());
        // check if testDatabase has been migrated if not migrate it

        try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            try {
                migrationService.checkMigrationRequired();
            } catch (DatabaseMigrationException ex) {
                Log.info("==== Attempting to run migration scripts ======");

                // Use MultiEmitter to print emitted items to System.out
                Multi.createFrom().emitter(emitter -> {
                    migrationService.runAllUnRunMigrations(testUtils.getTestRealm(), emitter);
                    migrationService.runAllUnRunMigrations(testUtils.getDefaultRealm(), emitter);
                    migrationService.runAllUnRunMigrations(testUtils.getSystemRealm(), emitter);
                }).subscribe().with(
                   item -> System.out.println(item),
                   failure -> System.err.println("Failed: " + failure.getMessage()),
                   () -> Log.error("Migration Complete"));


            }
        }

    }


    /* @BeforeEach
    protected void setUp() {


    }

    @AfterEach
    void tearDown() {

    } */
}
