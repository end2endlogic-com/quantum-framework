package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.TenantProvisioningRun;
import com.e2eq.framework.model.security.TenantProvisioningWorkflow;
import com.e2eq.framework.model.persistent.morphia.TenantProvisioningRunRepo;
import com.e2eq.framework.service.TenantProvisioningService;
import com.e2eq.framework.util.EnvConfigUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantProvisioningPinnedRunTest {
    @Test void planIsStoredBeforeSideEffectsAndRestartRetryUsesIt() {
        Repo repo=new Repo(); Provisioner provisioner=new Provisioner(repo); var planner=TenantInitializationPlannerTest.planner();
        var first=service(repo,provisioner,planner);
        var command=TenantProvisioningService.ProvisionTenantCommand.builder()
            .adminUserId("operator").adminCredentialType(CredentialType.EMAIL).applicationId("app").build();
        String ref="pinned-operation-00001";
        assertThrows(TenantProvisioningRunFailedException.class,()->first.startProvisioning(command,ref));
        assertNotNull(repo.run.getInitializationPlanJson());
        assertEquals(TenantInitializationPlanner.digest(repo.run.getInitializationPlanJson()),repo.run.getInitializationPlanSha256());
        assertTrue(repo.run.isRealmCreated());
        String json=repo.run.getInitializationPlanJson();
        assertEquals("FAILED",first.startProvisioning(command,ref).getStatus());
        assertEquals(1,provisioner.catalogCalls);
        // Different service/planner instance after restart; refuse changed runtime before taking a claim.
        var nextPlanner=TenantInitializationPlannerTest.planner(); nextPlanner.runtimeArtifactSha256=Optional.of("2".repeat(64));
        var restarted=service(repo,provisioner,nextPlanner);
        assertTrue(assertThrows(IllegalStateException.class,()->restarted.retry(ref,null))
            .getMessage().startsWith("PROVISIONING_PLAN_RUNTIME_DRIFT"));
        assertEquals(0,repo.claims); assertEquals(1,provisioner.identityCalls);
        nextPlanner.runtimeArtifactSha256=Optional.of("1".repeat(64));
        assertEquals("COMPLETED",restarted.retry(ref,null).getStatus());
        assertEquals(json,repo.run.getInitializationPlanJson());
        assertEquals(1,provisioner.catalogCalls,"completed receipt skips catalog execution");
        assertEquals(2,provisioner.identityCalls); assertEquals(1,repo.run.getRetryCount());
        assertTrue(repo.run.isRealmCreated(),"completed-step results survive retry");
    }

    @Test void legacyFailedRunDoesNotAcquireClaimOrResolveLatestSeeds() {
        Repo repo=new Repo(); repo.run=TenantProvisioningRun.builder().executionRef("legacy-operation-00001")
            .status(TenantProvisioningRun.Status.FAILED).deploymentTopology("POOLED_REALM").build();
        var provisioner=new Provisioner(repo); var service=service(repo,provisioner,TenantInitializationPlannerTest.planner());
        assertTrue(assertThrows(IllegalStateException.class,()->service.retry(repo.run.getExecutionRef(),null))
            .getMessage().startsWith("PROVISIONING_PLAN_UNPINNED"));
        assertEquals(0,repo.claims); assertEquals(0,provisioner.catalogCalls);
    }

    TenantProvisioningRunService service(Repo repo,Provisioner provisioner,TenantInitializationPlanner planner) {
        var service=new TenantProvisioningRunService(); service.runRepo=repo; service.provisioningService=provisioner;
        service.initializationPlanner=planner; service.envConfigUtils=new EnvConfigUtils(){ @Override public String getSystemRealm(){return "authority";} };
        service.workflowDefaults=new TenantProvisioningWorkflowDefaults(); service.workflowDefaults.objectMapper=new ObjectMapper();
        String definition=service.workflowDefaults.buildDefinitionJson();
        service.workflowService=new TenantProvisioningWorkflowService(){ @Override public TenantProvisioningWorkflow resolveCurrentWorkflow(String realm){
            return TenantProvisioningWorkflow.builder().refName("default").workflowVersion(1).workflowDefinitionJson(definition).build(); } };
        return service;
    }
    static class Repo extends TenantProvisioningRunRepo {
        TenantProvisioningRun run; int claims;
        @Override public Optional<TenantProvisioningRun> findByExecutionRef(String realm,String ref){return Optional.ofNullable(run);}
        @Override public TenantProvisioningRun save(String realm,TenantProvisioningRun value){run=value; return value;}
        @Override public boolean claimFailedRun(String realm,String ref){claims++;return true;}
    }
    static class Provisioner extends TenantProvisioningService {
        final Repo repo; int catalogCalls,identityCalls;
        Provisioner(Repo repo){this.repo=repo;}
        @Override public ProvisioningContext initializeContext(ProvisionTenantCommand command){
            var context=TenantInitializationPlannerTest.context(); context.setCommand(command); context.setResult(new ProvisionResult()); return context;
        }
        @Override public void ensureRealmCatalog(ProvisioningContext context){
            assertNotNull(repo.run.getInitializationPlanJson(),"plan must be persisted before catalog mutation");
            catalogCalls++; context.getResult().realmCreated=true;
        }
        @Override public void ensureRealmMembership(ProvisioningContext context){}
        @Override public void runRealmMigrations(ProvisioningContext context){throw new AssertionError("pooled migration must be skipped");}
        @Override public void ensureTenantIdentities(ProvisioningContext context){if(++identityCalls==1)throw new IllegalStateException("interrupted identity step");}
        @Override public void applyBaseSeedPacks(ProvisioningContext context){}
        @Override public void verifyRealmInitialization(ProvisioningContext context){}
    }
}
