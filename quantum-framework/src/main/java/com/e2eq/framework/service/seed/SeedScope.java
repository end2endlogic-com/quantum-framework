package com.e2eq.framework.service.seed;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.util.List;

/**
 * Optional scope filter for a seed pack manifest. When present and scopes are enabled,
 * determines whether a seed pack is applicable to the current SeedContext.
 *
 * Realm filtering is an additional constraint that applies regardless of scope type:
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

    private ScopeType type;            // one of GLOBAL, PER_TENANT, TENANT_LIST, ARCHETYPE, CUSTOM
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
    }
}
