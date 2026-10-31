package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.rest.models.AccessibleTenantInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tenants a principal may enter in the active login realm.
 *
 * <p>A dedicated / single-tenant realm has exactly one tenant, taken from the
 * realm DomainContext. A shared / multi-tenant realm lists only tenants named
 * on the user's assignment; an empty grant is not a wildcard.
 */
public final class AccessibleTenantResolver {

    private AccessibleTenantResolver() {
    }

    public static boolean sharedMultiTenant(Realm realm) {
        if (realm == null) {
            return false;
        }
        return realm.getTenancyMode() == RealmTenancyMode.MULTI_TENANT
                || realm.getDeploymentType() == RealmDeploymentType.SHARED;
    }

    public static List<AccessibleTenantInfo> resolve(
            Realm realm,
            UserRealmRole assignment,
            List<RealmTenantMembership> memberships) {
        if (realm == null) {
            return List.of();
        }
        if (!sharedMultiTenant(realm)) {
            AccessibleTenantInfo dedicated = AccessibleTenantInfo.fromDedicatedRealm(realm);
            return dedicated == null ? List.of() : List.of(dedicated);
        }

        Set<String> authorized = new LinkedHashSet<>();
        if (assignment != null && assignment.getAuthorizedTenantIds() != null) {
            assignment.getAuthorizedTenantIds().stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isBlank())
                    .forEach(authorized::add);
        }
        String authorizedPattern = assignment == null ? null : assignment.getAuthorizedTenantRegEx();
        if (authorized.isEmpty() && (authorizedPattern == null || authorizedPattern.isBlank())) {
            return List.of();
        }
        if (memberships == null || memberships.isEmpty()) {
            return List.of();
        }

        List<AccessibleTenantInfo> tenants = new ArrayList<>();
        for (RealmTenantMembership membership : memberships) {
            if (membership == null || membership.getTenantId() == null) {
                continue;
            }
            if (RealmTenantMembership.PARTICIPATION_STATUS_SUSPENDED.equals(
                    membership.getParticipationStatus())) {
                continue;
            }
            if (!isAuthorized(assignment, membership.getTenantId())) {
                continue;
            }
            AccessibleTenantInfo info = AccessibleTenantInfo.fromMembership(membership);
            if (info != null) {
                tenants.add(info);
            }
        }
        return tenants;
    }

    /** Shared authorization predicate used by both discovery and request enforcement. */
    public static boolean isAuthorized(UserRealmRole assignment, String tenantId) {
        if (assignment == null || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        String normalized = tenantId.trim();
        if (assignment.getAuthorizedTenantIds() != null && assignment.getAuthorizedTenantIds().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .anyMatch(normalized::equals)) {
            return true;
        }
        String expression = assignment.getAuthorizedTenantRegEx();
        if (expression == null || expression.isBlank()) {
            return false;
        }
        if ("*".equals(expression.trim())) {
            return true;
        }
        try {
            return Pattern.compile(expression.trim(), Pattern.CASE_INSENSITIVE)
                    .matcher(normalized)
                    .matches();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }
}
