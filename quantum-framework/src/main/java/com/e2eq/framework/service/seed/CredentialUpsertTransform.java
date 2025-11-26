package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.security.DomainContext;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.quarkus.arc.Arc;

import java.util.*;

/**
 * Seed transform that interprets each record as a credential upsert intent and
 * delegates to UserManagement so that passwords are hashed and roles are applied via
 * the supported auth provider. The intent document is also returned (possibly augmented)
 * so the repository can persist a harmless marker document for idempotency tracking.
 *
 * Expected record fields:
 * - userId (String) REQUIRED
 * - password (String) OPTIONAL (discouraged for production); used if passwordEnvVar is absent
 * - passwordEnvVar (String) OPTIONAL env var name providing the password
 * - roles (List<String>) OPTIONAL; applied only on create
 * - subject (String) OPTIONAL stable subject identifier
 * - forceChangePassword (Boolean) OPTIONAL defaults to false
 *
 * Behavior:
 * - If the userId does not exist, calls UserManagement.createUser(userId, password, forceChange, roles, domainContext)
 * - If the userId exists, does nothing (idempotent). Future enhancement: align roles or rotate password.
 */
@RegisterForReflection
final class CredentialUpsertTransform implements SeedTransform {

    private final AuthProviderFactory authProviderFactory;

    CredentialUpsertTransform(AuthProviderFactory authProviderFactory) {
        this.authProviderFactory = authProviderFactory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(Map<String, Object> record, SeedContext context, SeedPackManifest.Dataset dataset) {
        Map<String, Object> out = new LinkedHashMap<>(record);

        String userId = asString(record.get("userId"));
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("credentialUpsert requires 'userId'");
        }

        // Resolve password: prefer env var indirection
        String password = null;
        String envVar = asString(record.get("passwordEnvVar"));
        if (envVar != null && !envVar.isBlank()) {
            password = Optional.ofNullable(System.getenv(envVar))
                    .orElseGet(() -> {
                        // try MP config as a fallback
                        try {
                            return io.quarkus.arc.Arc.container().instance(org.eclipse.microprofile.config.ConfigProvider.class).isAvailable() ?
                                    org.eclipse.microprofile.config.ConfigProvider.getConfig().getOptionalValue(envVar, String.class).orElse(null) : null;
                        } catch (Throwable t) {
                            return null;
                        }
                    });
            if (password == null) {
                Log.warnf("credentialUpsert: env var %s not found for user %s; skipping password set", envVar, userId);
            }
        }
        if (password == null) {
            password = asString(record.get("password"));
        }

        List<String> roles = asStringList(record.get("roles"));
        String subject = asString(record.get("subject"));
        Boolean forceChangePassword = asBoolean(record.get("forceChangePassword"), Boolean.FALSE);

        // Build DomainContext from SeedContext
        DomainContext dc = DomainContext.builder()
                .tenantId(context.getTenantId().orElse(null))
                .orgRefName(context.getOrgRefName().orElse(null))
                .accountId(context.getAccountId().orElse(null))
                .defaultRealm(context.getRealm())
                .build();

        try {
            UserManagement userManagement = authProviderFactory.getUserManager();
            boolean exists = userManagement.userIdExists(userId);
            if (!exists) {
                Set<String> roleSet = roles == null ? Set.of() : new LinkedHashSet<>(roles);
                if (password == null || password.isBlank()) {
                    Log.warnf("credentialUpsert: creating user %s without password (null/blank); consider setting passwordEnvVar", userId);
                }
                userManagement.createUser(userId, password, forceChangePassword, roleSet, dc);
                Log.infof("credentialUpsert: created credential for %s in realm %s", userId, context.getRealm());
            } else {
                Log.infof("credentialUpsert: user %s already exists in realm %s; skipping (idempotent)", userId, context.getRealm());
            }
        } catch (Exception e) {
            Log.errorf("credentialUpsert failed for %s in realm %s: %s", userId, context.getRealm(), e.getMessage());
            throw new IllegalStateException("credentialUpsert failed for userId=" + userId + ": " + e.getMessage(), e);
        }

        // Tag the intent with applied metadata; repository will upsert the intent document as a harmless marker.
        out.putIfAbsent("appliedAt", new Date().toString());
        out.put("realmId", context.getRealm());
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : Objects.toString(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?>) {
            List<String> list = new ArrayList<>();
            for (Object v : (List<?>) o) list.add(Objects.toString(v));
            return list;
        }
        // single string
        return List.of(Objects.toString(o));
    }

    private static Boolean asBoolean(Object o, Boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(Objects.toString(o));
    }

    /** Factory registered under transform key "credentialUpsert". */
    public static final class Factory implements SeedTransformFactory {
        @Override
        public SeedTransform create(SeedPackManifest.Transform transformDefinition) {
            AuthProviderFactory apf = Arc.container().instance(AuthProviderFactory.class).get();
            return new CredentialUpsertTransform(apf);
        }
    }
}
