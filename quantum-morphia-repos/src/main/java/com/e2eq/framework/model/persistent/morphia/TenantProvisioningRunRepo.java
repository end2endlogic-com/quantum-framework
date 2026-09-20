package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.TenantProvisioningRun;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class TenantProvisioningRunRepo extends MorphiaRepo<TenantProvisioningRun> {

    public boolean claimFailedRun(String realm, String executionRef) {
        return getMorphiaDataStoreWrapper().getDataStore(realm).getCollection(TenantProvisioningRun.class)
                .updateOne(com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("executionRef", executionRef),
                        com.mongodb.client.model.Filters.eq("status", TenantProvisioningRun.Status.FAILED.name())),
                        com.mongodb.client.model.Updates.set("status", TenantProvisioningRun.Status.RUNNING.name()))
                .getModifiedCount() == 1;
    }

    public Optional<TenantProvisioningRun> findByExecutionRef(String realm, String executionRef) {
        return Optional.ofNullable(
            getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(TenantProvisioningRun.class)
                .filter(Filters.eq("executionRef", executionRef))
                .first()
        );
    }
}
