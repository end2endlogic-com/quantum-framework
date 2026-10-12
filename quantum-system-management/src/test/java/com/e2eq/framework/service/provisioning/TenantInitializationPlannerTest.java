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
    static void failure(String code,Runnable action) {
        assertTrue(assertThrows(IllegalStateException.class,action::run).getMessage().startsWith(code));
    }
}
