package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;

/** Shared typed mapping at the generated control-plane contract boundary. */
public final class ControlPlaneRealmMapper {
    private ControlPlaneRealmMapper() {
    }

    public static Realm fromEntry(RealmCatalogEntry entry) {
        Realm realm = new Realm();
        realm.setRefName(entry.getRefName());
        realm.setDisplayName(entry.getDisplayName());
        realm.setDatabaseName(entry.getDatabaseName());
        realm.setDeploymentType(parseDeploymentType(entry.getDeploymentType()));
        realm.setEmailDomain(entry.getEmailDomain());
        realm.setConnectionString(entry.getConnectionString());
        if (hasText(entry.getTenantId()) && hasText(entry.getOrgRefName())
                && hasText(entry.getAccountNumber())) {
            realm.setDomainContext(DomainContext.builder()
                .tenantId(entry.getTenantId())
                .orgRefName(entry.getOrgRefName())
                .accountId(entry.getAccountNumber())
                .defaultRealm(entry.getRefName())
                .build());
        }
        return realm;
    }

    public static RealmCatalogEntry toEntry(Realm realm) {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName(realm.getRefName());
        entry.setDisplayName(realm.getDisplayName());
        entry.setDatabaseName(realm.getDatabaseName());
        entry.setDeploymentType(realm.getDeploymentType().name());
        entry.setEmailDomain(realm.getEmailDomain());
        entry.setConnectionString(realm.getConnectionString());
        if (realm.getDomainContext() != null) {
            entry.setTenantId(realm.getDomainContext().getTenantId());
            entry.setOrgRefName(realm.getDomainContext().getOrgRefName());
            entry.setAccountNumber(realm.getDomainContext().getAccountId());
        }
        return entry;
    }

    public static RealmTenantMembership fromEntry(RealmMembershipEntry entry) {
        RealmTenantMembership membership = new RealmTenantMembership();
        membership.setRefName(entry.getOrganizationRefName() + "-" + entry.getRealmRefName());
        membership.setRealmRefName(entry.getRealmRefName());
        membership.setOrganizationRefName(entry.getOrganizationRefName());
        membership.setAccountId(entry.getAccountId());
        membership.setTenantId(entry.getTenantId());
        membership.setMembershipRole(entry.getMembershipRole());
        membership.setParticipationStatus(entry.getParticipationStatus());
        return membership;
    }

    public static RealmMembershipEntry toEntry(RealmTenantMembership membership) {
        RealmMembershipEntry entry = new RealmMembershipEntry();
        entry.setRealmRefName(membership.getRealmRefName());
        entry.setOrganizationRefName(membership.getOrganizationRefName());
        entry.setAccountId(membership.getAccountId());
        entry.setTenantId(membership.getTenantId());
        entry.setMembershipRole(membership.getMembershipRole());
        entry.setParticipationStatus(membership.getParticipationStatus());
        return entry;
    }

    public static UserRealmRole fromEntry(UserRealmRoleEntry entry) {
        UserRealmRole role = new UserRealmRole();
        role.setRefName(entry.getUserId() + "-" + entry.getRealmRefName());
        role.setUserId(entry.getUserId());
        role.setSubject(entry.getUserId());
        role.setRealmRefName(entry.getRealmRefName());
        role.setRoles(entry.getRoles());
        role.setAuthorizedApplications(entry.getAuthorizedApplications());
        role.setDefaultApplication(entry.getDefaultApplication());
        role.setSponsoringOrgRefName(entry.getSponsoringOrgRefName());
        role.setStatus(entry.getStatus());
        return role;
    }

    public static UserRealmRoleEntry toEntry(UserRealmRole role) {
        UserRealmRoleEntry entry = new UserRealmRoleEntry();
        entry.setUserId(role.getUserId());
        entry.setRealmRefName(role.getRealmRefName());
        entry.setRoles(role.getRoles());
        entry.setAuthorizedApplications(role.getAuthorizedApplications());
        entry.setDefaultApplication(role.getDefaultApplication());
        entry.setSponsoringOrgRefName(role.getSponsoringOrgRefName());
        entry.setStatus(role.getStatus());
        return entry;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static RealmDeploymentType parseDeploymentType(String value) {
        if (!hasText(value)) {
            return RealmDeploymentType.DEDICATED;
        }
        try {
            return RealmDeploymentType.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                "Unsupported realm deploymentType from control plane: " + value,
                error);
        }
    }
}
