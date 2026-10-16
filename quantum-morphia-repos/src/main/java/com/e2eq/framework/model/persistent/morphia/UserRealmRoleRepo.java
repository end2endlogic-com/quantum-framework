package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.UserRealmRole;
import com.mongodb.MongoException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserRealmRoleRepo extends MorphiaRepo<UserRealmRole> {

    /** Name of the unique (realmRefName, userId) index: one assignment per user and realm. */
    public static final String UNIQUE_ASSIGNMENT_INDEX = "uidx_user_realm_role_realm_user";

    /**
     * Creates the unique (realmRefName, userId) index in the system realm if it is missing. It is
     * not declared on the entity because building it at datastore initialization would fail that
     * initialization (and with it sign-in) on a deployment that already holds duplicate
     * assignments. Writers that depend on the guarantee call this first and fail closed: a
     * duplicate-key error is reported with the remedy instead of being ignored. Idempotent.
     */
    public void ensureUniqueAssignmentIndex(String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        String collection = ds.getMapper().getEntityModel(UserRealmRole.class).collectionName();
        try {
            ds.getDatabase().getCollection(collection).createIndex(
                Indexes.ascending("realmRefName", "userId"),
                new IndexOptions().unique(true).name(UNIQUE_ASSIGNMENT_INDEX));
        }
        catch (MongoException e) {
            throw new IllegalStateException(
                "Cannot create unique index " + UNIQUE_ASSIGNMENT_INDEX + " on " + systemRealmId + "." + collection
                    + ": duplicate (realmRefName, userId) assignments exist; merge them, then retry", e);
        }
    }

    public List<UserRealmRole> findByUserIdWithIgnoreRules(String userId, String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        try (var cursor = ds.find(UserRealmRole.class)
                .filter(Filters.eq("userId", userId))
                .iterator()) {
            return cursor.toList();
        }
    }

    public List<UserRealmRole> findByRealmRefNameWithIgnoreRules(String realmRefName, String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        try (var cursor = ds.find(UserRealmRole.class)
                .filter(Filters.eq("realmRefName", realmRefName))
                .iterator()) {
            return cursor.toList();
        }
    }

    public List<UserRealmRole> findAllWithIgnoreRules(String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        try (var cursor = ds.find(UserRealmRole.class).iterator()) {
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
