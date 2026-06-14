package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.UserRealmRole;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRealmRoleRepo extends MorphiaRepo<UserRealmRole> {

    /**
     * Active per-realm roles for a user, bypassing security rules — used by
     * the unauthenticated login path exactly like CredentialRepo.findByUserId
     * (ignoreRules): at token issuance there is no security context yet.
     * Assignments live in the system realm next to credentials.
     */
    public List<String> findActiveRolesForRealmWithIgnoreRules(String userId, String realmRefName, String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        Optional<UserRealmRole> assignment;
        try (var cursor = ds.find(UserRealmRole.class)
                .filter(Filters.eq("userId", userId), Filters.eq("realmRefName", realmRefName))
                .iterator()) {
            assignment = cursor.hasNext() ? Optional.of(cursor.next()) : Optional.empty();
        }
        return assignment
            .filter(a -> !UserRealmRole.STATUS_SUSPENDED.equals(a.getStatus()))
            .map(UserRealmRole::getRoles)
            .orElse(List.of());
    }
}
