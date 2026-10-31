package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.rest.models.AccessibleRealmContext;
import com.e2eq.framework.rest.models.AccessibleRealmInfo;
import com.e2eq.framework.rest.models.AccessibleTenantInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure resolver shared by auth discovery and its contract tests. */
public final class ApplicationRealmContextResolver {

    private ApplicationRealmContextResolver() {
    }

    public static List<AccessibleRealmContext> resolve(
            String applicationId,
            CredentialUserIdPassword credential,
            List<Realm> allowedRealms,
            List<UserRealmRole> assignments,
            Function<String, List<RealmTenantMembership>> membershipsForRealm) {
        if (applicationId == null || applicationId.isBlank() || credential == null || allowedRealms == null) {
            return List.of();
        }
        String requestedApplication = applicationId.trim();
        Map<String, UserRealmRole> byRealm = assignments == null
                ? Map.of()
                : assignments.stream()
                    .filter(value -> value != null && value.getRealmRefName() != null)
                    .filter(value -> !UserRealmRole.STATUS_SUSPENDED.equals(value.getStatus()))
                    .collect(Collectors.toMap(
                            UserRealmRole::getRealmRefName,
                            value -> value,
                            (first, ignored) -> first));

        List<AccessibleRealmContext> contexts = new ArrayList<>();
        for (Realm realm : allowedRealms) {
            if (!ownedBy(realm, requestedApplication)) {
                continue;
            }
            UserRealmRole assignment = byRealm.get(realm.getRefName());
            ApplicationAuthorizationResolver.Result applicationAccess = ApplicationAuthorizationResolver.resolve(
                    assignment == null ? null : assignment.getAuthorizedApplications(),
                    credential.getApplicationRegEx(),
                    assignment == null ? null : assignment.getDefaultApplication(),
                    requestedApplication);
            if (!applicationAccess.resolved()) {
                continue;
            }
            List<RealmTenantMembership> memberships = AccessibleTenantResolver.sharedMultiTenant(realm)
                    ? safeMemberships(membershipsForRealm, realm.getRefName())
                    : List.of();
            List<AccessibleTenantInfo> tenants = AccessibleTenantResolver.resolve(realm, assignment, memberships);
            if (AccessibleTenantResolver.sharedMultiTenant(realm) && tenants.isEmpty()) {
                continue;
            }
            contexts.add(new AccessibleRealmContext(
                    requestedApplication,
                    AccessibleRealmInfo.fromRealm(realm),
                    new ArrayList<>(tenants)));
        }
        contexts.sort(Comparator.comparing(
                value -> value.getRealm().getDisplayName() == null
                        ? value.getRealm().getRefName()
                        : value.getRealm().getDisplayName(),
                String.CASE_INSENSITIVE_ORDER));
        return contexts;
    }

    private static boolean ownedBy(Realm realm, String applicationId) {
        return realm != null
                && realm.getApplicationRef() != null
                && realm.getApplicationRef().getEntityRefName() != null
                && realm.getApplicationRef().getEntityRefName().equalsIgnoreCase(applicationId);
    }

    private static List<RealmTenantMembership> safeMemberships(
            Function<String, List<RealmTenantMembership>> membershipsForRealm,
            String realmRefName) {
        if (membershipsForRealm == null) {
            return List.of();
        }
        List<RealmTenantMembership> memberships = membershipsForRealm.apply(realmRefName);
        return memberships == null ? List.of() : memberships;
    }
}
