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

        String realm = context.getRealmId();
        String tenantId = context.getTenantId().orElse(null);
        String archetype = null; // SeedContext currently does not expose archetype; extend later as needed

        switch (scope.getType()) {
            case GLOBAL:
                // Always applicable; idempotency remains per-realm via registry keys
                return true;
            case PER_TENANT:
                // Applicable when we have a tenantId present in context
                return tenantId != null && !tenantId.isBlank();
            case TENANT_LIST:
                return tenantId != null && contains(scope.getTenants(), tenantId);
            case ARCHETYPE:
                // Until SeedContext carries archetype, default to not applicable when enabled and archetype missing
                return archetype != null && contains(scope.getArchetypes(), archetype);
            case CUSTOM:
                // Reserved for future hook; for safety, do not apply by default
                return false;
            default:
                return true;
        }
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
