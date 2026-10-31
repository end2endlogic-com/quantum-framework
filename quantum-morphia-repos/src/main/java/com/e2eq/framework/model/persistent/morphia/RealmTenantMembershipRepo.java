package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.RealmTenantMembership;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class RealmTenantMembershipRepo extends MorphiaRepo<RealmTenantMembership> {
    public List<RealmTenantMembership> findByOrganizationRefNameWithIgnoreRules(
            String systemRealmId, String organizationRefName) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(systemRealmId)
                .find(RealmTenantMembership.class)
                .filter(Filters.eq("organizationRefName", organizationRefName))
                .iterator()) {
            return cursor.toList();
        }
    }

    public List<RealmTenantMembership> findByRealmRefNameWithIgnoreRules(String systemRealmId, String realmRefName) {
        try (var cursor = morphiaDataStoreWrapper.getDataStore(systemRealmId)
                .find(RealmTenantMembership.class)
                .filter(Filters.eq("realmRefName", realmRefName))
                .iterator()) {
            return cursor.toList();
        }
    }
}
