package com.e2eq.framework.model.persistent.migration.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationPlanTest {
    @Test void resolvesReadOnlyExactCatalogAndIgnoresNewMigrationsOnResume() throws Exception {
        Bean first = new Bean("first"), second = new Bean("second");
        MigrationPlan plan = MigrationPlanSupport.plan("tenant", "1.0.7", "LOCAL", List.of(first, second));
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsBytes(plan), MigrationPlan.class);
        assertEquals(List.of(first, second), MigrationPlanSupport.validate(restored, "1.0.7", "LOCAL",
            List.of(first, second, new Bean("new-after-plan"))));
    }
    @Test void metadataAndImplementationChangesRejectBeforeExecution() {
        Bean bean = new Bean("first");
        var plan = MigrationPlanSupport.plan("tenant", "1.0.7", "LOCAL", List.of(bean));
        bean.checksum = "changed";
        failure("MIGRATION_PLAN_DEFINITION_DRIFT", () -> MigrationPlanSupport.validate(plan,"1.0.7","LOCAL",List.of(bean)));
        bean.checksum = null; bean.priority++;
        failure("MIGRATION_PLAN_DEFINITION_DRIFT", () -> MigrationPlanSupport.validate(plan,"1.0.7","LOCAL",List.of(bean)));
        Bean differentCode = new Bean("first") { @Override public void execute(MorphiaSession s, MongoClient c, MultiEmitter<? super String> e) { } };
        failure("MIGRATION_PLAN_DEFINITION_DRIFT", () -> MigrationPlanSupport.validate(plan,"1.0.7","LOCAL",List.of(differentCode)));
    }
    @Test void databaseTargetOrScopeChangeRequiresExplicitUpgrade() {
        var plan = MigrationPlanSupport.plan("tenant", "1.0.7", "LOCAL", List.of(new Bean("first")));
        failure("MIGRATION_PLAN_RUNTIME_DRIFT", () -> MigrationPlanSupport.validate(plan,"1.0.8","LOCAL",List.of()));
        failure("MIGRATION_PLAN_RUNTIME_DRIFT", () -> MigrationPlanSupport.validate(plan,"1.0.7","REMOTE",List.of()));
    }
    @Test void ambiguityMissingAndForeignRealmFailClosed() {
        Bean bean = new Bean("first");
        failure("MIGRATION_CATALOG_AMBIGUOUS", () -> MigrationPlanSupport.plan("tenant","1.0.7","LOCAL",List.of(bean,bean)));
        var plan = MigrationPlanSupport.plan("tenant", "1.0.7", "LOCAL", List.of(bean));
        failure("MIGRATION_PLAN_ENTRY_UNAVAILABLE", () -> MigrationPlanSupport.validate(plan,"1.0.7","LOCAL",List.of()));
        bean.override = "other";
        failure("MIGRATION_PLAN_CROSS_REALM_UNSUPPORTED", () -> MigrationPlanSupport.plan("tenant","1.0.7","LOCAL",List.of(bean)));
    }
    @Test void emptyApplicabilityMeansAllAndExplicitOtherRealmIsExcluded() {
        Bean global = new Bean("global"), other = new Bean("other");
        global.realms = Set.of(); other.realms = Set.of("elsewhere");
        var plan = MigrationPlanSupport.plan("tenant","1.0.7","LOCAL",List.of(global,other));
        assertEquals(List.of("global"), plan.entries().stream().map(MigrationPlan.Entry::name).toList());
    }
    @Test void exactSuccessfulReceiptSuppressesExecutionAndLegacyIsNotTrusted() {
        var entry = MigrationPlanSupport.pin(new Bean("first"));
        assertFalse(MigrationPlanSupport.alreadyApplied(entry, Optional.empty()));
        ChangeSetRecord record = new ChangeSetRecord();
        record.setSuccessful(true); record.setChangeSetVersion(1);
        failure("MIGRATION_RECEIPT_UNPINNED", () -> MigrationPlanSupport.alreadyApplied(entry,Optional.of(record)));
        record.setDefinitionSha256(entry.definitionSha256());
        assertTrue(MigrationPlanSupport.alreadyApplied(entry,Optional.of(record)));
        record.setDefinitionSha256("0".repeat(64));
        failure("MIGRATION_RECEIPT_CONFLICT", () -> MigrationPlanSupport.alreadyApplied(entry,Optional.of(record)));
        record.setChangeSetVersion(2);
        failure("MIGRATION_RECEIPT_NEWER_THAN_PLAN", () -> MigrationPlanSupport.alreadyApplied(entry,Optional.of(record)));
        record.setChangeSetVersion(0); assertFalse(MigrationPlanSupport.alreadyApplied(entry,Optional.of(record)));
        record.setSuccessful(false);
        failure("MIGRATION_RECEIPT_UNSUCCESSFUL", () -> MigrationPlanSupport.alreadyApplied(entry,Optional.of(record)));
    }
    @Test void realmPlanningDoesNotAccessDatastore() {
        var service = new MigrationService() { @Override public List<ChangeSetBean> getAllChangeSetBeans() { return List.of(new Bean("first")); } };
        service.targetDatabaseVersion="1.0.7"; service.databaseScope="LOCAL";
        // No datastore, repo, Mongo or emitter is wired.
        assertEquals(1, service.planRealm("tenant").entries().size());
    }
    @Test void migrationBeyondTargetIsNotSilentlyExecuted() {
        failure("MIGRATION_CATALOG_TARGET_CONFLICT", () -> MigrationPlanSupport.plan("tenant","1.0.0","LOCAL",List.of(new Bean("first"))));
    }
    @Test void remoteApplicationRuntimeExcludesSystemCatalogMigrations() {
        var application=new Bean("application");
        var system=new Bean("system") { @Override public DataPlane getDataPlane() { return DataPlane.SYSTEM_CATALOG; } };
        assertTrue(MigrationService.ownsChangeSet("remote",application));
        assertFalse(MigrationService.ownsChangeSet("remote",system));
        assertTrue(MigrationService.ownsChangeSet("embedded",system));
    }
    private static void failure(String code, Runnable action) {
        assertTrue(assertThrows(IllegalStateException.class, action::run).getMessage().startsWith(code));
    }
    static class Bean extends ChangeSetBase {
        String name,checksum,override; int priority=1; Set<String> realms;
        Bean(String name) { this.name=name; }
        public String getId(){return name;} public String getName(){return name;}
        public int getDbFromVersionInt(){return 1000000;} public int getDbToVersionInt(){return 1000007;}
        public String getDbFromVersion(){return "1.0.0";} public String getDbToVersion(){return "1.0.7";}
        public int getPriority(){return priority;} public String getAuthor(){return "test";}
        public String getDescription(){return "test migration";} public String getScope(){return "LOCAL";}
        public String getChecksum(){return checksum;} public Set<String> getApplicableDatabases(){return realms;}
        public boolean isOverrideDatabase(){return override!=null;} public String getOverrideDatabaseName(){return override;}
        public void execute(MorphiaSession s,MongoClient c,MultiEmitter<? super String> e){throw new AssertionError("Planning must not execute");}
    }
}
