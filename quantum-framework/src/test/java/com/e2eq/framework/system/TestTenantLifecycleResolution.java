package com.e2eq.framework.system;

import com.e2eq.framework.api.tenant.TenantLifecycle;
import com.e2eq.framework.service.TenantProvisioningService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * In embedded mode the TenantLifecycle contract resolves to the embedded
 * TenantProvisioningService — consumers can inject the contract today and be
 * unaffected when the Phase C remote implementation arrives.
 */
@QuarkusTest
public class TestTenantLifecycleResolution {

    @Inject
    TenantLifecycle tenantLifecycle;

    @Test
    public void embeddedModeResolvesToTenantProvisioningService() {
        Assertions.assertNotNull(tenantLifecycle);
        // ArC injects a client proxy; unwrap by class name check on the proxied type.
        Assertions.assertTrue(
            tenantLifecycle instanceof TenantProvisioningService,
            "expected the embedded TenantProvisioningService, got: " + tenantLifecycle.getClass());
    }
}
