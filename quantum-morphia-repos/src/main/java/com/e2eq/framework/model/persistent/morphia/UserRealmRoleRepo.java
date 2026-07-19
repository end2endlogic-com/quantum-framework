package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.UserRealmRole;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRealmRoleRepo extends MorphiaRepo<UserRealmRole> {

    public List<UserRealmRole> findByRealmRefNameWithIgnoreRules(String realmRefName, String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        try (var cursor = ds.find(UserRealmRole.class)
                .filter(Filters.eq("realmRefName", realmRefName))
                .iterator()) {
            return cursor.toList();
        }
    }

    /**
     * Active per-realm roles for a user, bypassing security rules — used by
     * the unauthenticated login path exactly like CredentialRepo.findByUserId
     * (ignoreRules): at token issuance there is no security context yet.
     * Assignments live in the system realm next to credentials.
     */
    public List<String> findActiveRolesForRealmWithIgnoreRules(String userId, String realmRefName, String systemRealmId) {
        return findActiveAssignmentForRealmWithIgnoreRules(userId, realmRefName, systemRealmId)
            .map(UserRealmRole::getRoles)
            .orElse(List.of());
    }

    /**
     * The user's active (non-suspended) membership assignment for a realm, bypassing
     * security rules — same unauthenticated login path as {@link #findActiveRolesForRealmWithIgnoreRules}.
     * Used at token issuance to read both the realm roles and the application grant.
     */
    public Optional<UserRealmRole> findActiveAssignmentForRealmWithIgnoreRules(String userId, String realmRefName, String systemRealmId) {
        return findAssignmentForRealmWithIgnoreRules(userId, realmRefName, systemRealmId)
            .filter(a -> !UserRealmRole.STATUS_SUSPENDED.equals(a.getStatus()));
    }

    /**
     * The user's membership assignment for a realm regardless of status, bypassing
     * security rules. Used by the admin surface to read/mutate the application grant
     * (which must live in — and be read back from — the system realm the login path uses).
     */
    public Optional<UserRealmRole> findAssignmentForRealmWithIgnoreRules(String userId, String realmRefName, String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        try (var cursor = ds.find(UserRealmRole.class)
                .filter(Filters.eq("userId", userId), Filters.eq("realmRefName", realmRefName))
                .iterator()) {
            return cursor.hasNext() ? Optional.of(cursor.next()) : Optional.empty();
        }
    }
}
