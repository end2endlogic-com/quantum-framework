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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
@DefaultBean
public class DefaultDataDomainResolver implements DataDomainResolver {

    /**
     * Distinguished sentinel returned by the LEGACY in-band {@code FROM_SOURCE} resolution path
     * (the 4-arg {@link #resolveForCreate(String, String, Object, SourceAttributes)}) when a
     * REQUIRED DataDomain component (orgRefName, accountNum, tenantId) cannot be derived from the
     * source attributes (null/blank/missing).
     *
     * <p>NOTE (S3): this sentinel is RETAINED only for the inert 4-arg API and its S1/S2 tests,
     * which compare it by identity ({@code ==}). The S1 code_review flagged that {@code ==} contract
     * as fail-OPEN-fragile (a cloned/round-tripped DataDomain with these values is NOT {@code ==}
     * this instance and would slip through). The GOVERNED ingest path no longer uses this sentinel
     * at all — it uses the tagged {@link DataDomainResolution} (see {@link #resolveIngestRow}), where
     * the unresolvable decision is carried by the type, not by a DataDomain value. The 4-arg path
     * now delegates to that tagged resolution and maps {@code Unresolvable} back to this instance to
     * preserve byte-identical legacy behavior.</p>
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
        List<String> keys = buildSourceScopedKeys(fa, fd, attrs);

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

    /**
     * Build the precedence-ordered policy key list for the source/ingestion (4-arg) path.
     *
     * <p>When {@code attrs} carries a non-blank {@code sourceId}, a source-scoped namespace is
     * searched FIRST, most-specific to least-specific:
     * <pre>
     *   src/{sourceId}/{entityType}  (only when entityType is non-blank)
     *   src/{sourceId}/*
     *   src/*&#47;{entityType}            (only when entityType is non-blank)
     * </pre>
     * followed by the existing principal-namespace keys
     * ({@code fa:fd → fa:* → *:fd → *:*}).</p>
     *
     * <p>When {@code attrs} is null or its {@code sourceId} is blank, the returned list is EXACTLY
     * the legacy four keys, so resolution for ordinary entity creates is byte-identical to today.</p>
     */
    private List<String> buildSourceScopedKeys(String fa, String fd, SourceAttributes attrs) {
        List<String> legacy = Arrays.asList(
                fa + ":" + fd,
                fa + ":*",
                "*:" + fd,
                "*:*"
        );

        if (attrs == null || isBlank(attrs.getSourceId())) {
            return legacy;
        }

        String sourceId = attrs.getSourceId();
        String entityType = attrs.getEntityType();
        boolean hasEntityType = !isBlank(entityType);

        // Defense-in-depth: sourceId/entityType are interpolated into the policy key path
        // (src/{sourceId}/{entityType}). A value containing a key metacharacter (/ * :) could
        // forge a collision with a wildcard policy entry (e.g. sourceId="*" selecting a
        // cross-source "any source" entry) — a policy-selection/authz bypass once sourceId/
        // entityType arrive over REST (S6). Reject tainted values by falling through to the
        // legacy keys ("no usable source scope"); NEVER interpolate a tainted value into a key.
        if (containsKeyMetachar(sourceId) || (hasEntityType && containsKeyMetachar(entityType))) {
            return legacy;
        }

        List<String> keys = new ArrayList<>();
        if (hasEntityType) {
            keys.add("src/" + sourceId + "/" + entityType);
        }
        keys.add("src/" + sourceId + "/*");
        if (hasEntityType) {
            keys.add("src/*/" + entityType);
        }
        keys.addAll(legacy);
        return keys;
    }

    /**
     * True if {@code v} contains a policy-key metacharacter ({@code / * :}) that would let a
     * tainted source attribute forge or restructure a key path. Used to reject such values from
     * the {@code src/...} namespace (fail safe to the legacy keys) before they reach REST at S6.
     */
    private boolean containsKeyMetachar(String v) {
        return v != null && (v.indexOf('/') >= 0 || v.indexOf('*') >= 0 || v.indexOf(':') >= 0);
    }

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
     * Legacy adapter: evaluate a {@code FROM_SOURCE} entry to a bare {@link DataDomain}, mapping the
     * tagged {@link DataDomainResolution} back to the {@link #UNRESOLVABLE} sentinel so the inert
     * 4-arg API (and its S1/S2 identity-based tests) keep their byte-identical behavior. New code
     * MUST use {@link #resolveFromSourceTagged} / {@link #resolveIngestRow} and branch on the tag.
     */
    private DataDomain resolveFromSource(DataDomainPolicyEntry entry, SourceAttributes attrs) {
        DataDomainResolution res = resolveFromSourceTagged(entry, attrs);
        return res.isResolved() ? res.dataDomain() : UNRESOLVABLE;
    }

    /**
     * Evaluate a {@code FROM_SOURCE} entry's component bindings against the supplied source
     * attributes, returning a fail-closed tagged result. {@link DataDomainResolution.Resolved} when
     * all REQUIRED components (orgRefName, accountNum, tenantId) resolve to non-blank values,
     * otherwise {@link DataDomainResolution.Unresolvable} carrying the reason. Never returns null and
     * never synthesizes a principal/default placement.
     */
    private DataDomainResolution resolveFromSourceTagged(DataDomainPolicyEntry entry, SourceAttributes attrs) {
        DataDomainComponentBinding binding = entry.getComponentBinding();
        if (binding == null) {
            return DataDomainResolution.unresolvable("FROM_SOURCE entry has no componentBinding");
        }
        if (attrs == null) {
            return DataDomainResolution.unresolvable("no source attributes supplied for FROM_SOURCE entry");
        }

        String orgRefName = resolveString(binding.getOrgRefName(), attrs);
        String accountNum = resolveString(binding.getAccountNum(), attrs);
        String tenantId = resolveString(binding.getTenantId(), attrs);

        // Required components must be present and non-blank.
        if (isBlank(orgRefName) || isBlank(accountNum) || isBlank(tenantId)) {
            StringBuilder missing = new StringBuilder();
            if (isBlank(orgRefName)) missing.append("orgRefName ");
            if (isBlank(accountNum)) missing.append("accountNum ");
            if (isBlank(tenantId)) missing.append("tenantId ");
            return DataDomainResolution.unresolvable(
                    "required DataDomain component(s) could not be derived from source: " + missing.toString().trim());
        }

        // Optional components with sensible defaults.
        int dataSegment = resolveInt(binding.getDataSegment(), attrs, 0);
        String ownerId = resolveString(binding.getOwnerId(), attrs);
        if (isBlank(ownerId)) {
            ownerId = "system";
        }

        return DataDomainResolution.resolved(DataDomain.builder()
                .orgRefName(orgRefName)
                .accountNum(accountNum)
                .tenantId(tenantId)
                .dataSegment(dataSegment)
                .ownerId(ownerId)
                .build());
    }

    /**
     * Governed ingest resolution (S3). Resolves a single ingest row to a fail-closed
     * {@link DataDomainResolution} using ONLY the explicitly supplied {@code policy} and the row's
     * {@code attrs}. Does NOT consult {@link SecurityContext}/principal and never falls back to a
     * principal or configured default — if no matching entry can place the row, the result is
     * {@link DataDomainResolution.Unresolvable}.
     *
     * <p>Key precedence reuses the source-scoped keying from S2 ({@code src/{sourceId}/...} then the
     * legacy {@code fa:fd → fa:* → *:fd → *:*}). Only {@code FROM_SOURCE} entries can produce a
     * placement here; a {@code FIXED} entry yields its configured DataDomain; a
     * {@code FROM_CREDENTIAL} entry is unresolvable on the ingest path (there is no credential).</p>
     */
    @Override
    public DataDomainResolution resolveIngestRow(DataDomainPolicy policy,
                                                 String functionalArea,
                                                 String functionalDomain,
                                                 SourceAttributes attrs) {
        if (policy == null) {
            return DataDomainResolution.unresolvable("no DataDomainPolicy supplied for ingest source");
        }
        Map<String, DataDomainPolicyEntry> entries = policy.getPolicyEntries();
        if (entries == null || entries.isEmpty()) {
            return DataDomainResolution.unresolvable("DataDomainPolicy has no entries");
        }

        String fa = safe(functionalArea);
        String fd = safe(functionalDomain);
        List<String> keys = buildSourceScopedKeys(fa, fd, attrs);

        for (String key : keys) {
            DataDomainPolicyEntry entry = entries.get(key);
            if (entry == null) continue;

            DataDomainPolicyEntry.ResolutionMode mode = entry.getResolutionMode();
            if (mode == null) mode = DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL;
            switch (mode) {
                case FROM_SOURCE:
                    return resolveFromSourceTagged(entry, attrs);
                case FIXED:
                    if (entry.getDataDomains() != null && !entry.getDataDomains().isEmpty()) {
                        DataDomain dd = entry.getDataDomains().get(0);
                        if (dd != null) return DataDomainResolution.resolved(dd);
                    }
                    return DataDomainResolution.unresolvable(
                            "FIXED policy entry '" + key + "' has no configured DataDomain");
                case FROM_CREDENTIAL:
                default:
                    // The ingest path has no authenticated credential to resolve against.
                    return DataDomainResolution.unresolvable(
                            "policy entry '" + key + "' is FROM_CREDENTIAL; not resolvable on the ingest path");
            }
        }
        return DataDomainResolution.unresolvable("no policy entry matched keys " + keys);
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
