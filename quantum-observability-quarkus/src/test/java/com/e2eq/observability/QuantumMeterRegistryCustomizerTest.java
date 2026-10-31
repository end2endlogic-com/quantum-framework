package com.e2eq.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class QuantumMeterRegistryCustomizerTest {

    @Test
    void addsStandardIdentityTagsToMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QuantumMeterRegistryCustomizer customizer =
                new QuantumMeterRegistryCustomizer("quantum-auth-service", "production", "1.4.1-SNAPSHOT", "auth-00042");

        customizer.customize(registry);
        Counter counter = registry.counter("quantum.test.requests");

        assertEquals("quantum-auth-service", counter.getId().getTag("service"));
        assertEquals("production", counter.getId().getTag("environment"));
        assertEquals("1.4.1-SNAPSHOT", counter.getId().getTag("version"));
        assertEquals("auth-00042", counter.getId().getTag("revision"));
    }

    @Test
    void normalizesBlankIdentityValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QuantumMeterRegistryCustomizer customizer =
                new QuantumMeterRegistryCustomizer(" ", null, "", " ");

        customizer.customize(registry);
        Counter counter = registry.counter("quantum.test.defaults");

        assertEquals("unknown", counter.getId().getTag("service"));
        assertEquals("unknown", counter.getId().getTag("environment"));
        assertEquals("unknown", counter.getId().getTag("version"));
        assertEquals("local", counter.getId().getTag("revision"));
    }
}
