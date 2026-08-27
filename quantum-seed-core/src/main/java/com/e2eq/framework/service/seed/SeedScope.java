package com.e2eq.framework.service.seed;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.util.List;

/**
 * Optional scope filter for a seed pack manifest.
 *
 * {@code type} is tenant/realm breadth (GLOBAL vs PER_TENANT vs ARCHETYPE). It is
 * not an environment classifier: GLOBAL does not mean "safe for production".
 *
 * {@code lifecycle} is the environment classifier:
 * <ul>
 *   <li>{@code production} — issuer/product baseline; applied when
 *       {@code quantum.seed-pack.lifecycle} includes production (the default)</li>
 *   <li>{@code demo} — lab/demo data; applied only when the runtime lifecycle
 *       explicitly includes {@code demo}</li>
 * </ul>
 * Unlabeled packs are treated as production for compatibility.
 *
 * Realm filtering is an additional constraint:
 * <ul>
 *   <li>{@code includeRealms} — seed pack applies ONLY to listed realms</li>
 *   <li>{@code excludeRealms} — seed pack applies to ALL realms EXCEPT those listed</li>
 * </ul>
 * These two fields are mutually exclusive.
 */
@Data
@RegisterForReflection
public class SeedScope {
    public enum ScopeType { GLOBAL, PER_TENANT, TENANT_LIST, ARCHETYPE, CUSTOM }

    public static final String LIFECYCLE_PRODUCTION = "production";
    public static final String LIFECYCLE_DEMO = "demo";

    private ScopeType type;            // one of GLOBAL, PER_TENANT, TENANT_LIST, ARCHETYPE, CUSTOM
    private String lifecycle;          // production | demo; blank = production
    private List<String> tenants;      // when type = TENANT_LIST
    private List<String> archetypes;   // when type = ARCHETYPE
    private List<String> includeRealms; // apply ONLY to these realms (mutually exclusive with excludeRealms)
    private List<String> excludeRealms; // apply to all realms EXCEPT these (mutually exclusive with includeRealms)

    /** Backward compatibility: maps the old 'realms' field to includeRealms. */
    public void setRealms(List<String> realms) {
        this.includeRealms = realms;
    }

    public void validate(String source) {
        boolean hasInclude = includeRealms != null && !includeRealms.isEmpty();
        boolean hasExclude = excludeRealms != null && !excludeRealms.isEmpty();
        if (hasInclude && hasExclude) {
            throw new IllegalStateException(
                    "includeRealms and excludeRealms are mutually exclusive in manifest " + source);
        }
        if (lifecycle != null && !lifecycle.isBlank()) {
            String normalized = lifecycle.trim().toLowerCase(java.util.Locale.ROOT);
            if (!LIFECYCLE_PRODUCTION.equals(normalized) && !LIFECYCLE_DEMO.equals(normalized)) {
                throw new IllegalStateException(
                        "scope.lifecycle must be 'production' or 'demo' in manifest " + source
                                + ", found: " + lifecycle);
            }
        }
    }
}
