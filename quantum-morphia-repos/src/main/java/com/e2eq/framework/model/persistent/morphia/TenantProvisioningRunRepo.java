package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.TenantProvisioningRun;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class TenantProvisioningRunRepo extends MorphiaRepo<TenantProvisioningRun> {

    public Optional<TenantProvisioningRun> findByExecutionRef(String realm, String executionRef) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(TenantProvisioningRun.class)
                .filter(Filters.eq("executionRef", executionRef))
                .first()
        );
    }
}
