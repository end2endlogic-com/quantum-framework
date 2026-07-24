package com.e2eq.framework.service.seed;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.List;
import java.util.Objects;

/**
 * Evaluates whether a seed pack descriptor is applicable to the current SeedContext
 * based on the optional "scope" block in the SeedPackManifest.
 *
 * Behavior is gated by config property: quantum.seed-pack.scopes.enabled (default: false)
 */
public final class ScopeMatcher {
    private static final String ENABLED_PROP = "quantum.seed-pack.scopes.enabled";

    private ScopeMatcher() {}

    public static boolean isApplicable(SeedPackDescriptor descriptor, SeedContext context) {
        if (!isEnabled()) return true; // feature gate
        if (descriptor == null || descriptor.getManifest() == null) return true;
        SeedPackManifest manifest = descriptor.getManifest();
        SeedScope scope = manifest.getScope();
        if (scope == null || scope.getType() == null) return true; // default behavior

        String realm = context.getRealm();
        String tenantId = context.getTenantId().orElse(null);
        String archetype = null; // SeedContext currently does not expose archetype; extend later as needed

        // First: check scope type applicability
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

        // Second: apply realm include/exclude filter (independent of scope type)
        return isRealmAllowed(scope, realm);
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
}
