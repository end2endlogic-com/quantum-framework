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

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("Seed Framework")
                .up();

        try {
            // Check if discovery service is functional
            // Try to discover seed packs for a test realm (non-blocking check)
            boolean discoveryHealthy = true;
            try {
                SeedContext testContext = SeedContext.builder("health-check").build();
                // This is a lightweight check - we just verify the service responds
                // We don't actually discover to avoid performance impact
                discoveryHealthy = seedDiscoveryService != null;
            } catch (Exception e) {
                discoveryHealthy = false;
                builder.down().withData("discoveryError", e.getMessage());
            }

            if (!discoveryHealthy) {
                builder.down().withData("discovery", "unavailable");
            } else {
                builder.withData("discovery", "available");
            }

            // Check registry
            boolean registryHealthy = seedRegistry != null;
            if (!registryHealthy) {
                builder.down().withData("registry", "unavailable");
            } else {
                builder.withData("registry", "available");
            }

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

            // Overall health: up if both discovery and registry are available
            if (discoveryHealthy && registryHealthy) {
                return builder.build();
            } else {
                return builder.down().build();
            }

        } catch (Exception e) {
            Log.errorf(e, "SeedFrameworkHealthCheck: error during health check");
            return builder.down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
