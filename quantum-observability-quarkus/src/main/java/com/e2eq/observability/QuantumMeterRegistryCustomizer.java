package com.e2eq.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.micrometer.runtime.MeterRegistryCustomizer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Applies the shared identity tags used to correlate metrics across Quantum
 * service hosts and deployment revisions.
 */
@Singleton
public final class QuantumMeterRegistryCustomizer implements MeterRegistryCustomizer {

    private final String service;
    private final String environment;
    private final String version;
    private final String revision;

    @Inject
    public QuantumMeterRegistryCustomizer(
            @ConfigProperty(name = "quarkus.application.name", defaultValue = "unknown") String service,
            @ConfigProperty(name = "quantum.observability.environment", defaultValue = "unknown") String environment,
            @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown") String version,
            @ConfigProperty(name = "quantum.observability.revision", defaultValue = "local") String revision) {
        this.service = normalized(service, "unknown");
        this.environment = normalized(environment, "unknown");
        this.version = normalized(version, "unknown");
        this.revision = normalized(revision, "local");
    }

    @Override
    public void customize(MeterRegistry registry) {
        registry.config().commonTags(
                "service", service,
                "environment", environment,
                "version", version,
                "revision", revision);
    }

    @Override
    public int priority() {
        return 0;
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
