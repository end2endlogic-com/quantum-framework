package com.e2eq.framework.model.persistent.morphia;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which globally mapped Morphia entity classes should be admitted into
 * a concrete realm datastore.
 *
 * <p>Framework identity, policy, realm, organization, and OAuth records are
 * system-plane resources. They belong in the system/auth datastore, not in each
 * tenant application's data realm. Mapping them into every realm causes empty
 * or misleading security collections to be created during index initialization.
 */
public final class RealmEntityMappingPolicy {

    public static final String DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES =
        "com.e2eq.framework.model.security.,"
            + "com.e2eq.framework.model.persistent.security.,"
            + "com.e2eq.framework.oauth.model.";

    /**
     * Identity collections owned by quantum-auth-service. Remote application
     * JVMs must not map these even in their configured system realm.
     */
    public static final String DEFAULT_IDENTITY_ENTITY_CLASSES =
        "com.e2eq.framework.model.security.UserProfile,"
            + "com.e2eq.framework.model.security.UserGroup,"
            + "com.e2eq.framework.model.security.CredentialUserIdPassword,"
            + "com.e2eq.framework.model.security.CredentialRefreshToken";

    private RealmEntityMappingPolicy() {
    }

    public static boolean shouldMapToRealm(String realm,
                                           String systemRealm,
                                           Class<?> entityType,
                                           boolean mapGlobalResourcesToTenantRealms,
                                           String tenantExcludedEntityPackagePrefixesCsv,
                                           String tenantExcludedEntityClassesCsv) {
        return shouldMapToRealm(
            realm,
            systemRealm,
            entityType,
            mapGlobalResourcesToTenantRealms,
            false,
            tenantExcludedEntityPackagePrefixesCsv,
            tenantExcludedEntityClassesCsv);
    }

    public static boolean shouldMapToRealm(String realm,
                                           String systemRealm,
                                           Class<?> entityType,
                                           boolean mapGlobalResourcesToTenantRealms,
                                           boolean remoteMode,
                                           String tenantExcludedEntityPackagePrefixesCsv,
                                           String tenantExcludedEntityClassesCsv) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm must be non-null and non-blank");
        }
        if (systemRealm == null || systemRealm.isBlank()) {
            throw new IllegalArgumentException("systemRealm must be non-null and non-blank");
        }
        if (entityType == null) {
            throw new IllegalArgumentException("entityType must be non-null");
        }
        if (remoteMode && isIdentityEntity(entityType)) {
            return false;
        }
        if (realm.equals(systemRealm) || mapGlobalResourcesToTenantRealms) {
            return true;
        }
        return !isTenantExcludedEntity(entityType, tenantExcludedEntityPackagePrefixesCsv, tenantExcludedEntityClassesCsv);
    }

    public static boolean isIdentityEntity(Class<?> entityType) {
        return entityType != null && parseCsv(DEFAULT_IDENTITY_ENTITY_CLASSES).contains(entityType.getName());
    }

    public static boolean isTenantExcludedEntity(Class<?> entityType,
                                                 String tenantExcludedEntityPackagePrefixesCsv,
                                                 String tenantExcludedEntityClassesCsv) {
        String className = entityType.getName();
        Set<String> excludedClasses = parseCsv(tenantExcludedEntityClassesCsv);
        if (excludedClasses.contains(className)) {
            return true;
        }
        for (String prefix : parseCsv(tenantExcludedEntityPackagePrefixesCsv)) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }
}
