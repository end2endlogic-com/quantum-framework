package com.e2eq.framework.service.seed;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.util.List;

/**
 * Optional scope filter for a seed pack manifest. When present and scopes are enabled,
 * determines whether a seed pack is applicable to the current SeedContext.
 */
@Data
@RegisterForReflection
public class SeedScope {
    public enum ScopeType { GLOBAL, PER_TENANT, TENANT_LIST, ARCHETYPE, CUSTOM }

    private ScopeType type;            // one of GLOBAL, PER_TENANT, TENANT_LIST, ARCHETYPE, CUSTOM
    private List<String> tenants;      // when type = TENANT_LIST
    private List<String> archetypes;   // when type = ARCHETYPE
    private List<String> realms;       // optional explicit override list (not yet used)
}
