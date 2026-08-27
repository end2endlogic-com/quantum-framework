package com.e2eq.framework.service.seed;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates whether a seed pack descriptor is applicable to the current SeedContext
 * based on the optional "scope" block in the SeedPackManifest.
 *
 * Lifecycle (production vs demo) is always enforced. Tenant/realm {@code type}
 * filters are gated by {@code quantum.seed-pack.scopes.enabled} (default false).
 */
public final class ScopeMatcher {
    private static final String ENABLED_PROP = "quantum.seed-pack.scopes.enabled";
    private static final String LIFECYCLE_PROP = "quantum.seed-pack.lifecycle";

    private ScopeMatcher() {}

    public static boolean isApplicable(SeedPackDescriptor descriptor, SeedContext context) {
        if (descriptor == null || descriptor.getManifest() == null) return true;
        SeedScope scope = descriptor.getManifest().getScope();
        if (!isLifecycleAllowed(scope)) return false;
        if (!isEnabled()) return true;
        if (scope == null || scope.getType() == null) return true;

        String realm = context.getRealm();
        String tenantId = context.getTenantId().orElse(null);
        String archetype = null; // SeedContext currently does not expose archetype; extend later as needed

        boolean typeApplicable = switch (scope.getType()) {
            case GLOBAL -> true;
            case PER_TENANT -> tenantId != null && !tenantId.isBlank();
            case TENANT_LIST -> tenantId != null && contains(scope.getTenants(), tenantId);
            case ARCHETYPE -> archetype != null && contains(scope.getArchetypes(), archetype);
            case CUSTOM -> false;
        };

        if (!typeApplicable) {
            return false;
        }

        return isRealmAllowed(scope, realm);
    }

    /**
     * Demo packs apply only when {@code quantum.seed-pack.lifecycle} includes
     * {@code demo}. Unlabeled and production packs apply when the runtime
     * admits {@code production} (the default).
     */
    static boolean isLifecycleAllowed(SeedScope scope) {
        String packLifecycle = normalizeLifecycle(scope == null ? null : scope.getLifecycle());
        return allowedLifecycles().contains(packLifecycle);
    }

    /**
     * Checks whether the given realm is allowed by the seed scope's realm filters.
     * If includeRealms is set, the realm must be in the list.
     * If excludeRealms is set, the realm must NOT be in the list.
     * If neither is set, all realms are allowed.
     */
    static boolean isRealmAllowed(SeedScope scope, String realm) {
        List<String> includeRealms = scope.getIncludeRealms();
        List<String> excludeRealms = scope.getExcludeRealms();

        if (includeRealms != null && !includeRealms.isEmpty()) {
            return contains(includeRealms, realm);
        }
        if (excludeRealms != null && !excludeRealms.isEmpty()) {
            return !contains(excludeRealms, realm);
        }
        return true;
    }

    private static boolean contains(List<String> list, String value) {
        if (list == null || list.isEmpty() || value == null) return false;
        for (String s : list) {
            if (Objects.equals(s, value)) return true;
        }
        return false;
    }

    private static boolean isEnabled() {
        try {
            Config cfg = ConfigProvider.getConfig();
            return cfg.getOptionalValue(ENABLED_PROP, Boolean.class).orElse(Boolean.FALSE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Set<String> allowedLifecycles() {
        try {
            Config cfg = ConfigProvider.getConfig();
            String csv = cfg.getOptionalValue(LIFECYCLE_PROP, String.class)
                    .orElse(SeedScope.LIFECYCLE_PRODUCTION);
            return parseLifecycles(csv);
        } catch (Throwable t) {
            return Set.of(SeedScope.LIFECYCLE_PRODUCTION);
        }
    }

    static Set<String> parseLifecycles(String csv) {
        Set<String> allowed = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            allowed.add(SeedScope.LIFECYCLE_PRODUCTION);
            return allowed;
        }
        for (String part : csv.split(",")) {
            String value = normalizeLifecycle(part);
            if (SeedScope.LIFECYCLE_PRODUCTION.equals(value) || SeedScope.LIFECYCLE_DEMO.equals(value)) {
                allowed.add(value);
            }
        }
        if (allowed.isEmpty()) {
            allowed.add(SeedScope.LIFECYCLE_PRODUCTION);
        }
        return allowed;
    }

    static String normalizeLifecycle(String raw) {
        if (raw == null || raw.isBlank()) {
            return SeedScope.LIFECYCLE_PRODUCTION;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
