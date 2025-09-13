package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class DefaultDataDomainResolver implements DataDomainResolver {

    @Inject
    GlobalDataDomainPolicyProvider globalPolicyProvider;

    @Override
    public DataDomain resolveForCreate(String functionalArea, String functionalDomain) {
        // Fallback to principal domain as the default behavior
        DataDomain principalDD = SecurityContext.getPrincipalDataDomain()
                .orElseThrow(() -> new IllegalStateException("Principal context not providing a data domain, ensure you're logged in or passing a data domain structure"));

        // Build precedence keys
        String fa = safe(functionalArea);
        String fd = safe(functionalDomain);
        List<String> keys = Arrays.asList(
                fa + ":" + fd,
                fa + ":*",
                "*:" + fd,
                "*:*"
        );

        // 1) Principal-attached policy via PrincipalContext
        Optional<PrincipalContext> pcOpt = SecurityContext.getPrincipalContext();
        if (pcOpt.isPresent()) {
            DataDomainPolicy p = pcOpt.get().getDataDomainPolicy();
            DataDomain resolved = resolveFromPolicy(p, keys, principalDD);
            if (resolved != null) return resolved;
        }

        // 2) Global policy provider
        DataDomainPolicy global = globalPolicyProvider.getPolicy().orElse(null);
        DataDomain resolved = resolveFromPolicy(global, keys, principalDD);
        if (resolved != null) return resolved;

        // 3) Default behavior
        return principalDD;
    }

    private String safe(String v) { return v == null ? "*" : v; }

    private DataDomain resolveFromPolicy(DataDomainPolicy policy, List<String> keys, DataDomain defaultDD) {
        if (policy == null) return null;
        Map<String, DataDomainPolicyEntry> entries = policy.getPolicyEntries();
        if (entries == null || entries.isEmpty()) return null;

        for (String key : keys) {
            DataDomainPolicyEntry entry = entries.get(key);
            if (entry != null) {
                DataDomainPolicyEntry.ResolutionMode mode = entry.getResolutionMode();
                if (mode == null) mode = DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL;
                switch (mode) {
                    case FIXED:
                        if (entry.getDataDomains() != null && !entry.getDataDomains().isEmpty()) {
                            DataDomain dd = entry.getDataDomains().get(0);
                            if (dd != null) return dd;
                        }
                        // fallthrough to default
                    case FROM_CREDENTIAL:
                    default:
                        return defaultDD;
                }
            }
        }
        return null;
    }
}
