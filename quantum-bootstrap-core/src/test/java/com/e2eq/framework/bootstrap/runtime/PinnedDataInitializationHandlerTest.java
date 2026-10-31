package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.*;
import com.e2eq.framework.model.persistent.migration.base.*;
import com.e2eq.framework.service.seed.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PinnedDataInitializationHandlerTest {
    Map<String,Object> scope = Map.of("realmRef","realm","tenantRef","tenant","orgRefName","org","accountId","account");
    BootstrapStepRequest request(BootstrapStepKind kind, Map<String,Object> config) {
        return new BootstrapStepRequest("pack","1","app","demo","data",kind,
            BootstrapPackApplyMode.APPLY_MISSING,"svc",scope,config);
    }
    @Test void preparedSeedPlanIsExecutedWithoutAnotherDiscovery() {
        AtomicInteger plans = new AtomicInteger(), applied = new AtomicInteger();
        var plan = new SeedPlan(1,"1".repeat(64),List.of(new SeedPlan.Pack("demo","1.0.0","2".repeat(64),List.of())));
        var handler = new PinnedDataInitializationHandler(); handler.mapper = new ObjectMapper();
        handler.seeds = new SeedLoaderService() {
            @Override public SeedPlan planSeeds(SeedContext c,List<SeedPackRef> refs) {
                assertEquals("demo@=1.0.0",refs.get(0).toString()); assertEquals("tenant",c.getTenantId().orElseThrow());
                plans.incrementAndGet(); return plan;
            }
            @Override public void validatePlan(SeedContext c,SeedPlan p) { assertEquals(plan,p); }
            @Override public void applyPlan(SeedContext c,SeedPlan p) { assertEquals(plan,p); applied.incrementAndGet(); }
        };
        var config = handler.prepare(request(BootstrapStepKind.SEED_PACK,Map.of("seedPacks",List.of(Map.of("name","demo","version","1.0.0")))));
        assertEquals(0,applied.get());
        assertEquals(BootstrapStepStatus.COMPLETED,handler.execute(request(BootstrapStepKind.SEED_PACK,config)).status());
        assertEquals(1,plans.get()); assertEquals(1,applied.get());
    }
    @Test void seedRangesAndMissingStoredPlansAreRejected() {
        var handler = new PinnedDataInitializationHandler(); handler.mapper = new ObjectMapper();
        assertThrows(IllegalArgumentException.class,() -> handler.prepare(request(BootstrapStepKind.SEED_PACK,
            Map.of("seedPacks",List.of(Map.of("name","demo","version","^1.0.0"))))));
        assertThrows(IllegalStateException.class,() -> handler.execute(request(BootstrapStepKind.SEED_PACK,Map.of())));
    }
    @Test void migrationUsesStoredPlanAndRefusesRealmDrift() {
        AtomicInteger plans = new AtomicInteger(), applied = new AtomicInteger();
        var plan = new MigrationPlan(1,"realm","1.0.0","DEV",List.of());
        var handler = new PinnedDataInitializationHandler(); handler.mapper = new ObjectMapper();
        handler.migrations = new MigrationService() {
            @Override public MigrationPlan planRealm(String realm) { plans.incrementAndGet(); return plan; }
            @Override public void validatePlan(MigrationPlan p) { assertEquals(plan,p); }
            @Override public void runPlan(MigrationPlan p,io.smallrye.mutiny.subscription.MultiEmitter<? super String> e) {
                assertEquals(plan,p); applied.incrementAndGet();
            }
        };
        var config = handler.prepare(request(BootstrapStepKind.MIGRATIONS,Map.of()));
        assertEquals(0,applied.get()); handler.execute(request(BootstrapStepKind.MIGRATIONS,config));
        assertEquals(1,plans.get()); assertEquals(1,applied.get());
        var other = new BootstrapStepRequest("pack","1","app","demo","data",BootstrapStepKind.MIGRATIONS,
            BootstrapPackApplyMode.APPLY_MISSING,"svc",Map.of("realmRef","another"),config);
        assertThrows(IllegalStateException.class,() -> handler.execute(other)); assertEquals(1,applied.get());
    }
}
