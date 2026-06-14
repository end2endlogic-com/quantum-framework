package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.TenantProvisioningWorkflow;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class TenantProvisioningWorkflowRepo extends MorphiaRepo<TenantProvisioningWorkflow> {

    public Optional<TenantProvisioningWorkflow> findDefault(String realm) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(TenantProvisioningWorkflow.class)
                .filter(Filters.eq("refName", TenantProvisioningWorkflow.DEFAULT_REF_NAME))
                .first()
        );
    }

    public Optional<TenantProvisioningWorkflow> findActive(String realm) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(TenantProvisioningWorkflow.class)
                .filter(Filters.eq("workflowEnabled", true))
                .first()
        );
    }
}
