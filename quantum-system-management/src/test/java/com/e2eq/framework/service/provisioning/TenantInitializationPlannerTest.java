package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.api.tenant.TenantDeploymentTopology;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.service.TenantProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantInitializationPlannerTest {
    static TenantProvisioningService.ProvisioningContext context() {
        var command = TenantProvisioningService.ProvisionTenantCommand.builder().adminUserId("operator").build();
        return TenantProvisioningService.ProvisioningContext.builder().command(command)
            .applicationId("app").realmId("pool").tenantId("tenant-a")
            .domainContext(DomainContext.builder().tenantId("tenant-a").orgRefName("org").accountId("account").defaultRealm("pool").build())
            .deploymentTopology(TenantDeploymentTopology.POOLED_REALM).archetypes(List.of()).build();
    }
    static TenantInitializationPlanner planner() {
        var planner = new TenantInitializationPlanner();
        planner.mapper = new ObjectMapper(); planner.runtimeArtifactSha256 = Optional.of("1".repeat(64));
        return planner;
    }
    @Test void storedPlanRoundTripsAndCannotChangeScopeOrRuntime() {
        var planner = planner(); var context = context(); var plan = planner.plan(context);
        String json = planner.encode(plan);
        var restored = planner.decode(json, TenantInitializationPlanner.digest(json));
        assertEquals(plan, restored); planner.validate(restored, context);
        context.setTenantId("another-tenant");
        failure("PROVISIONING_PLAN_SCOPE_DRIFT", () -> planner.validate(restored, context));
        context.setTenantId("tenant-a"); planner.runtimeArtifactSha256=Optional.of("2".repeat(64));
        failure("PROVISIONING_PLAN_RUNTIME_DRIFT", () -> planner.validate(restored, context));
    }
    @Test void missingRuntimeCannotCreateAPlanAndCorruptStorageCannotResume() {
        var planner=planner(); var plan=planner.plan(context()); String json=planner.encode(plan);
        failure("PROVISIONING_PLAN_STORAGE_CORRUPT", () -> planner.decode(json+" ",TenantInitializationPlanner.digest(json)));
        failure("PROVISIONING_PLAN_UNPINNED", () -> planner.decode(null,null));
        planner.runtimeArtifactSha256=Optional.empty();
        failure("PROVISIONING_RUNTIME_IDENTITY_REQUIRED", () -> planner.plan(context()));
    }
    @Test void poolAdmissionCannotPlanRealmBootstrap() {
        var planner=planner(); var context=context(); context.setArchetypes(List.of("unsafe"));
        failure("PROVISIONING_PLAN_POOL_BOOTSTRAP_FORBIDDEN", () -> planner.plan(context));
    }
    @Test void applicationRuntimeOwnershipDoesNotPlanAuthorityMigrationsOrSeeds() {
        var planner=planner(); planner.initializationOwner=TenantInitializationPlan.InitializationOwner.APPLICATION_RUNTIME;
        var context=context(); context.setDeploymentTopology(TenantDeploymentTopology.DEDICATED_REALM);
        var plan=planner.plan(context);
        assertNull(plan.migrations()); assertNull(plan.baseSeeds());
        planner.validate(plan,context);
        planner.initializationOwner=TenantInitializationPlan.InitializationOwner.AUTHORITY;
        failure("PROVISIONING_PLAN_OWNER_DRIFT", () -> planner.validate(plan,context));
        planner.initializationOwner=TenantInitializationPlan.InitializationOwner.APPLICATION_RUNTIME;
        context.setArchetypes(List.of("unsafe"));
        failure("PROVISIONING_PLAN_APPLICATION_BOOTSTRAP_FORBIDDEN", () -> planner.plan(context));
    }
    @Test void authorityRunRecordsApplicationDataStepsAsSkipped() {
        var planner=planner(); planner.initializationOwner=TenantInitializationPlan.InitializationOwner.APPLICATION_RUNTIME;
        var context=context(); context.setDeploymentTopology(TenantDeploymentTopology.DEDICATED_REALM);
        context.setInitializationPlan(planner.plan(context));
        var runs=new TenantProvisioningRunService();
        for (String key: List.of(TenantProvisioningWorkflowDefaults.STEP_REALM_MIGRATIONS,TenantProvisioningWorkflowDefaults.STEP_BASE_SEED_PACKS)) {
            var step=new com.e2eq.framework.model.security.TenantProvisioningRun.StepState(); step.setKey(key);
            assertTrue(runs.shouldSkip(step,context));
        }
        var identity=new com.e2eq.framework.model.security.TenantProvisioningRun.StepState(); identity.setKey(TenantProvisioningWorkflowDefaults.STEP_TENANT_IDENTITIES);
        assertFalse(runs.shouldSkip(identity,context));
    }
    static void failure(String code,Runnable action) {
        assertTrue(assertThrows(IllegalStateException.class,action::run).getMessage().startsWith(code));
    }
}
