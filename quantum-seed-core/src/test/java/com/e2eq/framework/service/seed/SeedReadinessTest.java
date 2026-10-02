package com.e2eq.framework.service.seed;
import com.e2eq.framework.util.EnvConfigUtils;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class SeedReadinessTest {
 @Test void dependencyFailuresMakeReadinessDown() {
  var health=new SeedFrameworkHealthCheck();
  health.environment=new EnvConfigUtils(){@Override public String getSystemRealm(){return "configured-realm";}};
  health.seedMetrics=new SeedMetrics(){@Override public Map<String,Object> getSummary(){return Map.of("totalSuccess",0,"totalFailure",0,"totalRecordsApplied",0);}};
  health.seedRegistry=SeedRegistry.noop();
  health.seedDiscoveryService=new SeedDiscoveryService(){@Override public void checkReadiness(SeedContext context){assertEquals("configured-realm",context.getRealm());}};
  assertEquals(HealthCheckResponse.Status.UP,health.call().getStatus());
  health.seedRegistry=new MorphiaSeedRegistry(){@Override public void checkReadiness(SeedContext context){throw new IllegalStateException("database unavailable");}};
  assertEquals(HealthCheckResponse.Status.DOWN,health.call().getStatus());
  health.seedRegistry=SeedRegistry.noop();
  health.seedDiscoveryService=new SeedDiscoveryService(){@Override public void checkReadiness(SeedContext context)throws java.io.IOException{throw new java.io.IOException("source unavailable");}};
  assertEquals(HealthCheckResponse.Status.DOWN,health.call().getStatus());
 }
}
