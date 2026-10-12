package com.e2eq.framework.bootstrap;

import com.e2eq.framework.bootstrap.model.*;
import com.e2eq.framework.bootstrap.runtime.*;
import com.e2eq.framework.bootstrap.spi.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapRunCheckpointTest {
    static final String KEY="bootstrap-installation-001";
    String previous;
    @BeforeEach void runtime(){previous=System.getProperty("quantum.provisioning.runtime-artifact-sha256");System.setProperty("quantum.provisioning.runtime-artifact-sha256","1".repeat(64));}
    @AfterEach void restore(){if(previous==null)System.clearProperty("quantum.provisioning.runtime-artifact-sha256");else System.setProperty("quantum.provisioning.runtime-artifact-sha256",previous);}

    @Test void persistsBeforeFirstEffectAndCheckpointsEachCompletedStep() {
        DurableFixture repo=new DurableFixture(); AtomicInteger calls=new AtomicInteger();
        var service=service(repo,request -> {
            var stored=repo.find(KEY).orElseThrow();
            assertEquals(BootstrapPackRunStatus.RUNNING,stored.status());
            assertEquals(calls.get(),stored.steps().size());
            assertNotNull(stored.packDefinitionJson());
            assertEquals("1".repeat(64),stored.runtimeArtifactSha256());
            calls.incrementAndGet();return success();
        });
        var run=service.apply(request("tenant"),KEY);
        assertEquals(BootstrapPackRunStatus.COMPLETED,run.status());assertEquals(2,calls.get());
        assertEquals(2,run.steps().size());assertNotNull(run.completedAt());
    }
    @Test void restartObservesOriginalReceiptWithoutResolvingChangedCatalog() {
        DurableFixture repo=new DurableFixture();
        var first=service(repo,r -> success()).apply(request("tenant"),KEY);
        BootstrapPackRegistry unavailable=new BootstrapPackRegistry(){
            public List<BootstrapPackDefinition> list(){throw new AssertionError("must not resolve latest");}
            public Optional<BootstrapPackDefinition> find(String key){throw new AssertionError("must not resolve latest");}
        };
        var restarted=new BootstrapPackService(unavailable,List.of(),repo);
        System.setProperty("quantum.provisioning.runtime-artifact-sha256","2".repeat(64));
        assertEquals(first,restarted.apply(request("tenant"),KEY));
        assertEquals("BOOTSTRAP_REQUEST_CONFLICT",assertThrows(IllegalStateException.class,
            () -> restarted.apply(request("another-tenant"),KEY)).getMessage());
    }
    @Test void failedRunNeverImplicitlyReexecutes() {
        DurableFixture repo=new DurableFixture();AtomicInteger calls=new AtomicInteger();
        var service=service(repo,r -> {calls.incrementAndGet();throw new IllegalStateException("fault");});
        assertEquals(BootstrapPackRunStatus.FAILED,service.apply(request("tenant"),KEY).status());
        assertEquals(BootstrapPackRunStatus.FAILED,service.apply(request("tenant"),KEY).status());
        assertEquals(1,calls.get());
    }
    @Test void missingHandlerResultCannotProduceCompletionReceipt() {
        DurableFixture repo=new DurableFixture();
        var run=service(repo,r -> null).apply(request("tenant"),KEY);
        assertEquals(BootstrapPackRunStatus.FAILED,run.status());
        assertEquals("BOOTSTRAP_STEP_RESULT_MISSING",run.steps().get(0).details().get("message"));
        assertThrows(IllegalArgumentException.class,() -> new BootstrapStepResult(null,null,Map.of()));
    }
    @Test void lostCheckpointLeavesInterruptedRunObservableAndBlocksReplay() {
        DurableFixture repo=new DurableFixture(){@Override public synchronized BootstrapPackRun save(BootstrapPackRun r){throw new IllegalStateException("storage unavailable");}};
        AtomicInteger calls=new AtomicInteger();var service=service(repo,r -> {calls.incrementAndGet();return success();});
        assertThrows(IllegalStateException.class,() -> service.apply(request("tenant"),KEY));
        var observed=service.apply(request("tenant"),KEY);
        assertEquals(BootstrapPackRunStatus.RUNNING,observed.status());assertEquals(0,observed.steps().size());
        assertEquals(1,calls.get());
    }
    @Test void initialPersistenceFailurePreventsEffects() {
        DurableFixture repo=new DurableFixture(){@Override public synchronized Creation createIfAbsent(BootstrapPackRun r){throw new IllegalStateException("store unavailable");}};
        AtomicInteger calls=new AtomicInteger();var service=service(repo,r -> {calls.incrementAndGet();return success();});
        assertThrows(IllegalStateException.class,() -> service.apply(request("tenant"),KEY));
        assertEquals(0,calls.get());assertTrue(repo.list().isEmpty());
    }
    @Test void concurrentRequestsHaveOneExecutor() throws Exception {
        DurableFixture repo=new DurableFixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();var service=service(repo,r -> {
            if(calls.getAndIncrement()==0){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}
            return success();
        });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(() -> service.apply(request("tenant"),KEY));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(BootstrapPackRunStatus.RUNNING,service.apply(request("tenant"),KEY).status());
            release.countDown();assertEquals(BootstrapPackRunStatus.COMPLETED,first.get(10,TimeUnit.SECONDS).status());
            assertEquals(2,calls.get());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void missingRuntimeOrNonDurableStoreRejectBeforeHandlers() {
        System.clearProperty("quantum.provisioning.runtime-artifact-sha256");
        assertThrows(IllegalStateException.class,() -> service(new DurableFixture(),r -> {fail("must not execute");return success();}).apply(request("tenant"),KEY));
        assertThrows(IllegalStateException.class,() -> service(new InMemoryBootstrapPackRunRepository(),r -> {fail("must not execute");return success();}).apply(request("tenant"),KEY));
    }
    static ApplyBootstrapPackRequest request(String tenant){return new ApplyBootstrapPackRequest("pack",BootstrapPackApplyMode.APPLY_MISSING,"app","test","realm",tenant,null,"operator");}
    static BootstrapStepResult success(){return new BootstrapStepResult(BootstrapStepStatus.COMPLETED,BootstrapStepOutcome.CREATED,Map.of());}
    static class DurableFixture extends InMemoryBootstrapPackRunRepository { @Override public boolean isDurable(){return true;} }
    static BootstrapPackService service(BootstrapPackRunRepository repo,java.util.function.Function<BootstrapStepRequest,BootstrapStepResult> action) {
        var pack=new BootstrapPackDefinition("pack","1","app","profile",List.of(step("first",List.of()),step("second",List.of("first"))));
        var registry=new CdiBootstrapPackRegistry(List.of(() -> List.of(pack)));
        BootstrapPackStepHandler handler=new BootstrapPackStepHandler(){
            public boolean supports(BootstrapStepKind kind){return true;}
            public BootstrapStepResult execute(BootstrapStepRequest request){return action.apply(request);}
        };
        return new BootstrapPackService(registry,List.of(handler),repo);
    }
    static BootstrapPackStepDefinition step(String name,List<String> deps){return new BootstrapPackStepDefinition(name,name,BootstrapStepKind.APP_SERVICE,BootstrapStepApplyPolicy.WHEN_MISSING,deps,Map.of());}
}
