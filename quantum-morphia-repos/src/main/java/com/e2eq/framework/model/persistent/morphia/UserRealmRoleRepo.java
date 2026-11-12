package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.auth.TenantDefaults;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.UserRealmRole;
import com.mongodb.MongoException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import dev.morphia.transactions.MorphiaSession;
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

    /** Assignments in {@code realmRefName} that name {@code tenantId} explicitly in authorizedTenantIds. */
    public List<UserRealmRole> findByRealmRefNameAndAuthorizedTenantWithIgnoreRules(String realmRefName, String tenantId,
                                                                                    String systemRealmId) {
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(systemRealmId);
        try (var cursor = ds.find(UserRealmRole.class)
                .filter(Filters.eq("realmRefName", realmRefName), Filters.eq("authorizedTenantIds", tenantId))
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

    // ------------------------------------------------------------------ default tenant on save

    /**
     * Thrown when a save would let an identity reach several tenants of a shared realm while it
     * defines no default tenant there ({@code IDENTITY_ONLY_TOKENS_DESIGN.md}, "Tenancy models").
     */
    public static class TenantDefaultRequiredException extends IllegalArgumentException {
        public static final String CODE = "TENANT_DEFAULT_REQUIRED";

        public TenantDefaultRequiredException(String message) {
            super(CODE + ": " + message);
        }
    }

    @Override
    public UserRealmRole save(Datastore datastore, UserRealmRole value) {
        requireDefaultTenant(datastore, value);
        return super.save(datastore, value);
    }

    @Override
    public UserRealmRole save(MorphiaSession session, UserRealmRole value) {
        requireDefaultTenant(session, value);
        return super.save(session, value);
    }

    @Override
    public List<UserRealmRole> save(Datastore datastore, List<UserRealmRole> entities) {
        entities.forEach(entity -> requireDefaultTenant(datastore, entity));
        return super.save(datastore, entities);
    }

    @Override
    public List<UserRealmRole> save(MorphiaSession session, List<UserRealmRole> entities) {
        entities.forEach(entity -> requireDefaultTenant(session, entity));
        return super.save(session, entities);
    }

    /**
     * Refuses a grant that widens an identity's reach to several tenants of a shared
     * ({@code MULTI_TENANT}) realm when neither its credential's DomainContext tenant nor its
     * data-domain policy default is among them. Assignments, realms and credentials share the
     * system realm datastore, so the checks read it directly. Re-saving an unchanged or narrower
     * grant is allowed (an older record that breaks the rule fails resolution with
     * TENANT_SELECTION_REQUIRED instead), and so is a grant for an identity that has no
     * credential yet (an invite); resolution applies the rule once it signs in.
     */
    void requireDefaultTenant(Datastore datastore, UserRealmRole updated) {
        if (updated == null || !TenantDefaults.grantsSeveralTenants(updated)
                || updated.getRealmRefName() == null || updated.getUserId() == null) {
            return;
        }
        Realm realm = findRealm(datastore, updated.getRealmRefName());
        if (realm == null || realm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT) {
            return;
        }
        if (!TenantDefaults.widensTenantGrant(findStored(datastore, updated), updated)) {
            return;
        }
        CredentialUserIdPassword credential = findCredential(datastore, updated.getUserId());
        if (credential == null || TenantDefaults.authorizedDefault(credential, updated).isPresent()) {
            return;
        }
        throw new TenantDefaultRequiredException(String.format(
                "'%s' would reach several tenants in realm '%s' without a default tenant; set the credential's "
                        + "DomainContext tenant or its data-domain policy default (%s FIXED) to one of them first",
                updated.getUserId(), updated.getRealmRefName(), TenantDefaults.POLICY_DEFAULT_KEY));
    }

    Realm findRealm(Datastore datastore, String realmRefName) {
        return datastore.find(Realm.class).filter(Filters.eq("refName", realmRefName)).first();
    }

    UserRealmRole findStored(Datastore datastore, UserRealmRole updated) {
        return updated.getId() != null
                ? datastore.find(UserRealmRole.class).filter(Filters.eq("_id", updated.getId())).first()
                : datastore.find(UserRealmRole.class)
                        .filter(Filters.eq("userId", updated.getUserId()),
                                Filters.eq("realmRefName", updated.getRealmRefName()))
                        .first();
    }

    CredentialUserIdPassword findCredential(Datastore datastore, String userId) {
        return datastore.find(CredentialUserIdPassword.class).filter(Filters.eq("userId", userId)).first();
    }
}
