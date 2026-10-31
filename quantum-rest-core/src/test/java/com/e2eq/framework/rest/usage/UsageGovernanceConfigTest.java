package com.e2eq.framework.rest.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageGovernanceConfigTest {

    @Test
    void defaultsOffWithoutParsingDormantPolicyValues() {
        UsageGovernanceConfig config = new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.POLICY_REQUEST_LIMIT, "not-a-number",
                UsageGovernanceConfig.POLICY_REFILL_PERIOD, "not-a-duration"));

        assertEquals(UsageGovernanceMode.OFF, config.mode());
        assertFalse(config.active());
    }

    @Test
    void buildsValidatedPropertyPolicyManifest() {
        UsageGovernanceConfig config = new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "observe",
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.POLICY_ID, "api-basic",
                UsageGovernanceConfig.POLICY_VERSION, "2026-08-15",
                UsageGovernanceConfig.POLICY_REQUEST_LIMIT, "25",
                UsageGovernanceConfig.POLICY_REFILL_PERIOD, "PT30S",
                UsageGovernanceConfig.POLICY_ENDPOINTS, "SALES:ORDER:listOrders",
                UsageGovernanceConfig.ALLOW_UNMATCHED_ENDPOINTS, "false",
                UsageGovernanceConfig.MAX_TRACKED_KEYS, "50",
                UsageGovernanceConfig.IDLE_TIMEOUT, "PT2M"));

        assertEquals(UsageGovernanceMode.OBSERVE, config.mode());
        assertTrue(config.policy().appliesTo(new UsageEndpointIdentity("sales", "order", "listOrders")));
        assertEquals(25, config.policy().requestLimit());
        assertEquals(Duration.ofSeconds(30), config.policy().refillPeriod());
        assertFalse(config.allowUnmatchedEndpoints());
        assertEquals(50, config.maxTrackedKeys());
        assertEquals(Duration.ofMinutes(2), config.idleTimeout());
    }

    @Test
    void activeModeFailsFastOnInvalidConfiguration() {
        assertThrows(IllegalStateException.class, () -> new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "ENFORCE")));
        assertThrows(IllegalStateException.class, () -> new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "ENFORCE",
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.POLICY_REQUEST_LIMIT, "0")));
        assertThrows(IllegalStateException.class, () -> new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "ENFORCE",
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.POLICY_ENDPOINTS, "missing-parts")));
        assertThrows(IllegalStateException.class, () -> new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "enabled")));
        assertThrows(IllegalStateException.class, () -> new UsageGovernanceConfig(Map.of(
                UsageGovernanceConfig.MODE, "ENFORCE",
                UsageGovernanceConfig.OPENAPI_OPERATION_ID_STRATEGY, "METHOD",
                UsageGovernanceConfig.ALLOW_UNMATCHED_ENDPOINTS, "sometimes")));
    }
}
