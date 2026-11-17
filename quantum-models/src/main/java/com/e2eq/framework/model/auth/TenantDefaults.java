package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.UserRealmRole;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The default-tenant rule for shared ({@code MULTI_TENANT}) realms
 * ({@code IDENTITY_ONLY_TOKENS_DESIGN.md}, "Tenancy models"). Pure functions, no I/O.
 *
 * <p>An identity with access to more than one tenant in a realm must define a default
 * tenant, either in its credential (DomainContext {@code tenantId}) or in its data-domain
 * policy (the {@code *:*} entry in {@code FIXED} mode). The default applies only when the
 * realm assignment authorizes it. An identity with exactly one tenant uses that tenant.
 */
public final class TenantDefaults {

    /** The data-domain policy key whose FIXED data domain names the identity's default tenant. */
    public static final String POLICY_DEFAULT_KEY = "*:*";

    private TenantDefaults() {
    }

    /**
     * The default tenant candidates in precedence order: the credential's DomainContext
     * tenant, then the data-domain policy's default tenant. Blank values are dropped.
     */
    public static List<String> candidates(CredentialUserIdPassword credential) {
        if (credential == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        if (credential.getDomainContext() != null) {
            addIfPresent(out, credential.getDomainContext().getTenantId());
        }
        addIfPresent(out, policyDefaultTenant(credential.getDataDomainPolicy()));
        return new ArrayList<>(out);
    }

    /** The tenant of the data-domain policy's {@code *:*} FIXED entry, or null. */
    public static String policyDefaultTenant(DataDomainPolicy policy) {
        if (policy == null || policy.getPolicyEntries() == null) {
            return null;
        }
        DataDomainPolicyEntry entry = null;
        for (Map.Entry<String, DataDomainPolicyEntry> candidate : policy.getPolicyEntries().entrySet()) {
            if (candidate.getKey() != null && POLICY_DEFAULT_KEY.equals(candidate.getKey().trim())) {
                entry = candidate.getValue();
                break;
            }
        }
        if (entry == null || entry.getResolutionMode() != DataDomainPolicyEntry.ResolutionMode.FIXED
                || entry.getDataDomains() == null || entry.getDataDomains().isEmpty()) {
            return null;
        }
        DataDomain fixed = entry.getDataDomains().get(0);
        return fixed == null ? null : trimToNull(fixed.getTenantId());
    }

    /** The first default candidate the assignment authorizes, if any. */
    public static Optional<String> authorizedDefault(CredentialUserIdPassword credential, UserRealmRole assignment) {
        for (String candidate : candidates(credential)) {
            if (AccessibleTenantResolver.isAuthorized(assignment, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the assignment can reach more than one tenant: two or more distinct
     * {@code authorizedTenantIds}, or an {@code authorizedTenantRegEx} (a pattern can match
     * any number of tenants).
     */
    public static boolean grantsSeveralTenants(UserRealmRole assignment) {
        if (assignment == null) {
            return false;
        }
        if (trimToNull(assignment.getAuthorizedTenantRegEx()) != null) {
            return true;
        }
        return tenantIds(assignment).size() > 1;
    }

    /**
     * Whether {@code updated} reaches any tenant {@code existing} did not: a new tenant id or
     * a changed tenant pattern. A null {@code existing} means every grant is new.
     */
    public static boolean widensTenantGrant(UserRealmRole existing, UserRealmRole updated) {
        if (updated == null) {
            return false;
        }
        if (existing == null) {
            return grantsSeveralTenants(updated);
        }
        if (!Objects.equals(trimToNull(existing.getAuthorizedTenantRegEx()),
                trimToNull(updated.getAuthorizedTenantRegEx()))
                && trimToNull(updated.getAuthorizedTenantRegEx()) != null) {
            return true;
        }
        return !tenantIds(existing).containsAll(tenantIds(updated));
    }

    private static Set<String> tenantIds(UserRealmRole assignment) {
        Set<String> ids = new LinkedHashSet<>();
        if (assignment.getAuthorizedTenantIds() != null) {
            assignment.getAuthorizedTenantIds().forEach(id -> addIfPresent(ids, id));
        }
        return ids;
    }

    private static void addIfPresent(Set<String> out, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            out.add(trimmed);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
