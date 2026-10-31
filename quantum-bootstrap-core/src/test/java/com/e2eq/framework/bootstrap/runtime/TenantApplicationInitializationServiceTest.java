package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.bootstrap.model.*;
import com.e2eq.framework.bootstrap.spi.*;
import com.e2eq.framework.model.security.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TenantApplicationInitializationServiceTest {
    static final String RUNTIME = "1".repeat(64);
    static final String KEY = "application-install-001";
    String previous;
    AtomicInteger effects;
    TenantApplicationInitializationService service;
    BootstrapPackDefinition pack;
    boolean registered;
    @BeforeEach void setup() {
        previous = System.getProperty("quantum.provisioning.runtime-artifact-sha256");
        System.setProperty("quantum.provisioning.runtime-artifact-sha256", RUNTIME);
        effects = new AtomicInteger(); registered = true;
        pack = new BootstrapPackDefinition("app-demo", "1.0.0", "app", "demo", List.of(
            new BootstrapPackStepDefinition("seed", "seed", BootstrapStepKind.SEED_PACK,
                BootstrapStepApplyPolicy.WHEN_MISSING, List.of(), Map.of("version", "1"))));
        var registry = new CdiBootstrapPackRegistry(List.of(() -> List.of(pack)));
        BootstrapPackStepHandler handler = new BootstrapPackStepHandler() {
            public boolean supports(BootstrapStepKind kind) { return true; }
            public BootstrapStepResult execute(BootstrapStepRequest request) {
                effects.incrementAndGet(); return new BootstrapStepResult(BootstrapStepStatus.COMPLETED,
                    BootstrapStepOutcome.CREATED, Map.of());
            }
        };
        var store = new InMemoryBootstrapPackRunRepository() { public boolean isDurable() { return true; } };
        service = new TenantApplicationInitializationService();
        service.bootstrap = new BootstrapPackService(registry, List.of(handler), store);
        service.applicationId = Optional.of("app"); service.environmentRef = Optional.of("local-dev");
        service.runtimeSha = Optional.of(RUNTIME); service.mode = "remote"; service.directoryMode = Optional.empty();
        service.directory = (SystemDirectory) Proxy.newProxyInstance(SystemDirectory.class.getClassLoader(),
            new Class<?>[]{SystemDirectory.class}, (proxy, method, args) -> {
                if (!method.getName().equals("findRealmByRefName")) throw new AssertionError("Catalog must be read-only");
                return registered ? Optional.of(Realm.builder().refName("realm").emailDomain("realm.example")
                    .databaseName("realm").domainContext(DomainContext.builder().tenantId("tenant")
                        .orgRefName("org").accountId("account").defaultRealm("realm").build()).build()) : Optional.empty();
            });
    }
    @AfterEach void restore() {
        if (previous == null) System.clearProperty("quantum.provisioning.runtime-artifact-sha256");
        else System.setProperty("quantum.provisioning.runtime-artifact-sha256", previous);
    }
    InitializeTenantApplicationRequest request(String tenant, String digest) {
        return new InitializeTenantApplicationRequest(KEY,"app","local-dev","realm",tenant,"org","account",
            "app-demo","1.0.0",digest,RUNTIME);
    }
    @Test void pinnedApplicationRunsOnceAndReplayObservesReceiptAfterRuntimeCatalogChange() {
        var request = request("tenant", TenantApplicationInitializationService.packDigest(pack));
        var first = service.initialize(request,"service-subject");
        assertEquals(BootstrapPackRunStatus.COMPLETED, first.status());
        service.runtimeSha = Optional.of("2".repeat(64));
        pack = new BootstrapPackDefinition("app-demo","2.0.0","app","demo",List.of());
        assertEquals(first, service.initialize(request,"service-subject"));
        assertEquals(1, effects.get());
        assertEquals("INITIALIZATION_REQUEST_CONFLICT", assertThrows(TenantInitializationFailure.class,
            () -> service.initialize(request,"different-service")).code());
    }
    @Test void missingCentralRealmCannotCreateIdentityOrExecute() {
        registered = false;
        assertEquals("INITIALIZATION_REALM_NOT_REGISTERED",assertThrows(TenantInitializationFailure.class,
            () -> service.initialize(request("tenant",TenantApplicationInitializationService.packDigest(pack)),"svc")).code());
        assertEquals(0,effects.get());
    }
    @Test void embeddedCatalogIsRejectedBeforeEffects() {
        service.mode = "embedded";
        assertEquals("INITIALIZATION_CENTRAL_DIRECTORY_REQUIRED", assertThrows(TenantInitializationFailure.class,
            () -> service.initialize(request("tenant",TenantApplicationInitializationService.packDigest(pack)),"svc")).code());
        assertEquals(0,effects.get());
    }
    @Test void mismatchedTenantCannotExecute() {
        assertEquals("INITIALIZATION_REALM_SCOPE_MISMATCH", assertThrows(TenantInitializationFailure.class,
            () -> service.initialize(request("other",TenantApplicationInitializationService.packDigest(pack)),"svc")).code());
        assertEquals(0,effects.get());
    }
    @Test void sameVersionChangedContentCannotExecute() {
        assertEquals("INITIALIZATION_PACK_DRIFT", assertThrows(TenantInitializationFailure.class,
            () -> service.initialize(request("tenant","2".repeat(64)),"svc")).code());
        assertEquals(0,effects.get());
    }
    @Test void runtimeDriftCannotExecute() {
        service.runtimeSha = Optional.of("2".repeat(64));
        assertEquals("INITIALIZATION_RUNTIME_DRIFT",assertThrows(TenantInitializationFailure.class,
            () -> service.initialize(request("tenant",TenantApplicationInitializationService.packDigest(pack)),"svc")).code());
        assertEquals(0,effects.get());
    }
    @Test void bindingIsCheckedAgainstFrozenExecutionSnapshot() {
        String original = TenantApplicationInitializationService.packDigest(pack);
        pack = new BootstrapPackDefinition("app-demo","1.0.0","app","demo",List.of());
        var apply = new ApplyBootstrapPackRequest("app-demo",BootstrapPackApplyMode.APPLY_MISSING,"app",
            "local-dev","realm","tenant",null,"svc",Map.of("expectedPackVersion","1.0.0",
                "expectedPackSha256",original,"expectedRuntimeArtifactSha256",RUNTIME));
        assertEquals("BOOTSTRAP_PACK_DRIFT", assertThrows(IllegalStateException.class,
            () -> service.bootstrap.apply(apply,KEY)).getMessage());
        assertTrue(service.bootstrap.findRun(KEY).isEmpty()); assertEquals(0,effects.get());
    }
}
