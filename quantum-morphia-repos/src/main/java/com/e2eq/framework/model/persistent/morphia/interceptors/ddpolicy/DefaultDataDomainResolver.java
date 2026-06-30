package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.DataDomainComponentBinding;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
@DefaultBean
public class DefaultDataDomainResolver implements DataDomainResolver {

    /**
     * Distinguished sentinel returned by the {@code FROM_SOURCE} resolution path when a REQUIRED
     * DataDomain component (orgRefName, accountNum, tenantId) cannot be derived from the source
     * attributes (null/blank/missing). It is intentionally NOT the principal's DataDomain and NOT
     * any configured default — S3 (the edge write + quarantine wiring) checks for this exact
     * instance (via {@code ==}) to route the row to quarantine instead of stamping a fallback
     * placement. Identity comparison is the contract; do not clone or mutate this instance.
     */
    public static final DataDomain UNRESOLVABLE = DataDomain.builder()
            .orgRefName("__UNRESOLVABLE__")
            .accountNum("__UNRESOLVABLE__")
            .tenantId("__UNRESOLVABLE__")
            .dataSegment(0)
            .ownerId("__UNRESOLVABLE__")
            .build();

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
            DataDomain resolved = resolveFromPolicy(p, keys, principalDD, null);
            if (resolved != null) return resolved;
        }

        // 2) Global policy provider
        DataDomainPolicy global = globalPolicyProvider.getPolicy().orElse(null);
        DataDomain resolved = resolveFromPolicy(global, keys, principalDD, null);
        if (resolved != null) return resolved;

        // 3) Default behavior
        return principalDD;
    }

    @Override
    public DataDomain resolveForCreate(String functionalArea, String functionalDomain, Object entity) {
        if (entity instanceof UnversionedBaseModel baseModel && baseModel.getId() != null && baseModel.getDataDomain() != null) {
            return baseModel.getDataDomain();
        }
        if (entity instanceof UnversionedBaseModel baseModel && baseModel.getDataDomain() != null) {
            return baseModel.getDataDomain();
        }
        return resolveForCreate(functionalArea, functionalDomain);
    }

    /**
     * Source/ingestion create path. Routes matching {@code FROM_SOURCE} policy entries through
     * value-driven resolution against {@code attrs}. If a required DataDomain component cannot be
     * derived, returns the {@link #UNRESOLVABLE} sentinel rather than any principal or default
     * placement. FIXED and FROM_CREDENTIAL entries behave exactly as in the principal path.
     */
    @Override
    public DataDomain resolveForCreate(String functionalArea, String functionalDomain, Object entity, SourceAttributes attrs) {
        if (attrs == null) {
            // No source context: preserve legacy entity-aware behavior.
            return resolveForCreate(functionalArea, functionalDomain, entity);
        }

        // If the entity already carries a concrete DataDomain, honor it (parity with 3-arg path).
        if (entity instanceof UnversionedBaseModel baseModel && baseModel.getDataDomain() != null) {
            return baseModel.getDataDomain();
        }

        // Principal context is optional for source writes; only used as the default for
        // FIXED/FROM_CREDENTIAL entries that may also live in the same policy.
        DataDomain principalDD = SecurityContext.getPrincipalDataDomain().orElse(null);

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
            DataDomain resolved = resolveFromPolicy(pcOpt.get().getDataDomainPolicy(), keys, principalDD, attrs);
            if (resolved != null) return resolved;
        }

        // 2) Global policy provider
        DataDomainPolicy global = globalPolicyProvider.getPolicy().orElse(null);
        DataDomain resolved = resolveFromPolicy(global, keys, principalDD, attrs);
        if (resolved != null) return resolved;

        // 3) No policy matched. With no principal there is nothing to fall back to → unresolvable.
        return principalDD != null ? principalDD : UNRESOLVABLE;
    }

    private String safe(String v) { return v == null ? "*" : v; }

    private DataDomain resolveFromPolicy(DataDomainPolicy policy, List<String> keys, DataDomain defaultDD, SourceAttributes attrs) {
        if (policy == null) return null;
        Map<String, DataDomainPolicyEntry> entries = policy.getPolicyEntries();
        if (entries == null || entries.isEmpty()) return null;

        for (String key : keys) {
            DataDomainPolicyEntry entry = entries.get(key);
            if (entry != null) {
                DataDomainPolicyEntry.ResolutionMode mode = entry.getResolutionMode();
                if (mode == null) mode = DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL;
                switch (mode) {
                    case FROM_SOURCE:
                        return resolveFromSource(entry, attrs);
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

    /**
     * Evaluate a {@code FROM_SOURCE} entry's component bindings against the supplied source
     * attributes. Returns a freshly built {@link DataDomain} when all REQUIRED components
     * (orgRefName, accountNum, tenantId) resolve to non-blank values, otherwise the
     * {@link #UNRESOLVABLE} sentinel. Never returns null and never returns the principal DD.
     */
    private DataDomain resolveFromSource(DataDomainPolicyEntry entry, SourceAttributes attrs) {
        DataDomainComponentBinding binding = entry.getComponentBinding();
        if (binding == null || attrs == null) {
            return UNRESOLVABLE;
        }

        String orgRefName = resolveString(binding.getOrgRefName(), attrs);
        String accountNum = resolveString(binding.getAccountNum(), attrs);
        String tenantId = resolveString(binding.getTenantId(), attrs);

        // Required components must be present and non-blank.
        if (isBlank(orgRefName) || isBlank(accountNum) || isBlank(tenantId)) {
            return UNRESOLVABLE;
        }

        // Optional components with sensible defaults.
        int dataSegment = resolveInt(binding.getDataSegment(), attrs, 0);
        String ownerId = resolveString(binding.getOwnerId(), attrs);
        if (isBlank(ownerId)) {
            ownerId = "system";
        }

        return DataDomain.builder()
                .orgRefName(orgRefName)
                .accountNum(accountNum)
                .tenantId(tenantId)
                .dataSegment(dataSegment)
                .ownerId(ownerId)
                .build();
    }

    private String resolveString(DataDomainComponentBinding.Binding b, SourceAttributes attrs) {
        if (b == null) return null;
        Object raw = rawValue(b, attrs);
        return raw == null ? null : String.valueOf(raw);
    }

    private int resolveInt(DataDomainComponentBinding.Binding b, SourceAttributes attrs, int defaultValue) {
        if (b == null) return defaultValue;
        Object raw = rawValue(b, attrs);
        if (raw == null) return defaultValue;
        if (raw instanceof Number num) return num.intValue();
        try {
            String s = String.valueOf(raw).trim();
            return s.isEmpty() ? defaultValue : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Object rawValue(DataDomainComponentBinding.Binding b, SourceAttributes attrs) {
        DataDomainComponentBinding.Kind kind = b.getKind();
        if (kind == DataDomainComponentBinding.Kind.FROM_ATTRIBUTE) {
            return attrs.get(b.getAttributeName());
        }
        // LITERAL (default)
        return b.getLiteralValue();
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
