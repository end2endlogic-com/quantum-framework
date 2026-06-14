package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.TenantOnboardingWorkflow;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class TenantOnboardingWorkflowRepo extends MorphiaRepo<TenantOnboardingWorkflow> {

    public Optional<TenantOnboardingWorkflow> findDefault(String realm) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(TenantOnboardingWorkflow.class)
                .filter(Filters.eq("refName", TenantOnboardingWorkflow.DEFAULT_REF_NAME))
                .first()
        );
    }

    public Optional<TenantOnboardingWorkflow> findActive(String realm) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(TenantOnboardingWorkflow.class)
                .filter(Filters.eq("workflowEnabled", true))
                .first()
        );
    }
}
