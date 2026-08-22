package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationRealmContextResolverTest {

    @Test
    void returnsOnlyRealmsOwnedByRequestedApplication() {
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setApplicationRegEx("*");

        var result = ApplicationRealmContextResolver.resolve(
                "app-a", credential,
                List.of(dedicated("realm-a", "app-a", "tenant-a"),
                        dedicated("realm-b", "app-b", "tenant-b")),
                List.of(), ignored -> List.of());

        assertEquals(List.of("realm-a"), result.stream()
                .map(value -> value.getRealm().getRefName()).toList());
        assertEquals(List.of("tenant-a"), result.get(0).getTenants().stream()
                .map(value -> value.getTenantId()).toList());
    }

    @Test
    void sharedRealmReturnsOnlyAssignedTenantSubset() {
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setApplicationRegEx("app-a");
        Realm shared = realm("shared-a", "app-a", RealmTenancyMode.MULTI_TENANT,
                RealmDeploymentType.SHARED, "placeholder");
        UserRealmRole assignment = UserRealmRole.builder()
                .realmRefName("shared-a")
                .authorizedApplications(List.of("app-a"))
                .authorizedTenantRegEx("customer-(one|three)")
                .status(UserRealmRole.STATUS_ACTIVE)
                .build();

        var result = ApplicationRealmContextResolver.resolve(
                "app-a", credential, List.of(shared), List.of(assignment), ignored -> List.of(
                        membership("shared-a", "customer-one"),
                        membership("shared-a", "customer-two"),
                        membership("shared-a", "customer-three")));

        assertEquals(List.of("customer-one", "customer-three"), result.get(0).getTenants().stream()
                .map(value -> value.getTenantId()).toList());
    }

    private static Realm dedicated(String realm, String app, String tenant) {
        return realm(realm, app, RealmTenancyMode.SINGLE_TENANT,
                RealmDeploymentType.DEDICATED, tenant);
    }

    private static Realm realm(String refName, String app, RealmTenancyMode tenancy,
                               RealmDeploymentType deployment, String tenant) {
        return Realm.builder()
                .refName(refName)
                .displayName(refName)
                .emailDomain(refName + ".test")
                .databaseName(refName)
                .applicationRef(EntityReference.builder()
                        .entityRefName(app)
                        .entityDisplayName(app)
                        .build())
                .tenancyMode(tenancy)
                .deploymentType(deployment)
                .domainContext(DomainContext.builder()
                        .defaultRealm(refName)
                        .tenantId(tenant)
                        .orgRefName(tenant)
                        .accountId("1")
                        .build())
                .build();
    }

    private static RealmTenantMembership membership(String realm, String tenant) {
        return RealmTenantMembership.builder()
                .realmRefName(realm)
                .tenantId(tenant)
                .displayName(tenant)
                .organizationRefName(tenant)
                .accountId("1")
                .participationStatus(RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE)
                .build();
    }
}
