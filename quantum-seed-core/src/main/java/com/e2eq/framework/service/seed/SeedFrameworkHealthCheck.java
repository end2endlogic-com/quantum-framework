package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.util.Map;

/**
 * Health check for the seed framework.
 * Reports readiness based on seed discovery and registry availability.
 */
@Readiness
@ApplicationScoped
public class SeedFrameworkHealthCheck implements HealthCheck {

    @Inject
    SeedDiscoveryService seedDiscoveryService;

    @Inject
    SeedRegistry seedRegistry;

    @Inject
    SeedMetrics seedMetrics;

    @Inject
    com.e2eq.framework.util.EnvConfigUtils environment;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Seed Framework")
                .up();

        try {
            SeedContext context = SeedContext.builder(environment.getSystemRealm()).build();
            seedDiscoveryService.checkReadiness(context);
            seedRegistry.checkReadiness(context);
            builder.withData("discovery", "available").withData("registry", "available");

            // Add metrics summary
            Map<String, Object> metrics = seedMetrics.getSummary();
            Object succ = metrics.get("totalSuccess");
            Object fail = metrics.get("totalFailure");
            Object recs = metrics.get("totalRecordsApplied");

            long succVal = (succ instanceof Number) ? ((Number) succ).longValue() : Long.parseLong(String.valueOf(succ));
            long failVal = (fail instanceof Number) ? ((Number) fail).longValue() : Long.parseLong(String.valueOf(fail));
            long recsVal = (recs instanceof Number) ? ((Number) recs).longValue() : Long.parseLong(String.valueOf(recs));

            builder.withData("totalSuccess", succVal)
                   .withData("totalFailure", failVal)
                   .withData("totalRecordsApplied", recsVal);

            return builder.build();

        } catch (Exception e) {
            Log.errorf(e, "SeedFrameworkHealthCheck: error during health check");
            return builder.down()
                    .withData("error", "SEED_READINESS_CHECK_FAILED")
                    .build();
        }
    }
}
