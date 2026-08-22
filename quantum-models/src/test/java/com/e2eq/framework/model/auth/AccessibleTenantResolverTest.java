package com.e2eq.framework.model.auth;

import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.rest.models.AccessibleTenantInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessibleTenantResolverTest {

    @Test
    void dedicatedRealmExposesTheSingleRealmTenant() {
        Realm realm = dedicated("local-test", "local-test", "local-test-org");

        List<AccessibleTenantInfo> tenants = AccessibleTenantResolver.resolve(realm, null, List.of());

        assertEquals(1, tenants.size());
        assertEquals("local-test", tenants.get(0).getTenantId());
        assertEquals("local-test-org", tenants.get(0).getOrgRefName());
        assertEquals("local-test", tenants.get(0).getRealmRefName());
        assertFalse(AccessibleTenantResolver.sharedMultiTenant(realm));
    }

    @Test
    void sharedRealmListsOnlyAuthorizedActiveTenants() {
        Realm realm = shared("helixor-digitalworker-P1");
        UserRealmRole assignment = UserRealmRole.builder()
                .userId("local-test")
                .realmRefName("helixor-digitalworker-P1")
                .authorizedTenantIds(List.of("local-test", "missing-tenant"))
                .build();
        List<RealmTenantMembership> memberships = List.of(
                membership("local-test", "local-test-org", RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE),
                membership("other-tenant", "other-org", RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE),
                membership("suspended", "suspended-org", RealmTenantMembership.PARTICIPATION_STATUS_SUSPENDED)
        );

        List<AccessibleTenantInfo> tenants = AccessibleTenantResolver.resolve(realm, assignment, memberships);

        assertTrue(AccessibleTenantResolver.sharedMultiTenant(realm));
        assertEquals(List.of("local-test"), tenants.stream().map(AccessibleTenantInfo::getTenantId).toList());
        assertEquals("local-test-org", tenants.get(0).getOrgRefName());
    }

    @Test
    void sharedRealmWithNoTenantGrantIsEmpty() {
        Realm realm = shared("helixor-digitalworker-P1");
        UserRealmRole assignment = UserRealmRole.builder()
                .userId("local-test")
                .realmRefName("helixor-digitalworker-P1")
                .build();

        assertEquals(List.of(), AccessibleTenantResolver.resolve(
                realm, assignment, List.of(membership("local-test", "local-test-org", "ACTIVE"))));
    }

    @Test
    void sharedRealmUnionsExplicitAndRegexTenantGrants() {
        Realm realm = shared("helixor-digitalworker-P1");
        UserRealmRole assignment = UserRealmRole.builder()
                .userId("local-test")
                .realmRefName("helixor-digitalworker-P1")
                .authorizedTenantIds(List.of("explicit-tenant"))
                .authorizedTenantRegEx("customer-[0-9]+")
                .build();

        List<AccessibleTenantInfo> tenants = AccessibleTenantResolver.resolve(realm, assignment, List.of(
                membership("explicit-tenant", "explicit-org", RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE),
                membership("customer-42", "customer-org", RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE),
                membership("unassigned", "other-org", RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE)));

        assertEquals(List.of("explicit-tenant", "customer-42"),
                tenants.stream().map(AccessibleTenantInfo::getTenantId).toList());
    }

    @Test
    void invalidTenantRegexFailsClosed() {
        UserRealmRole assignment = UserRealmRole.builder()
                .authorizedTenantRegEx("[")
                .build();

        assertFalse(AccessibleTenantResolver.isAuthorized(assignment, "any-tenant"));
    }

    @Test
    void tenantWildcardMatchesAnyActiveMembership() {
        UserRealmRole assignment = UserRealmRole.builder()
                .authorizedTenantRegEx("*")
                .build();

        assertTrue(AccessibleTenantResolver.isAuthorized(assignment, "any-tenant"));
    }

    private static Realm dedicated(String refName, String tenantId, String orgRefName) {
        return Realm.builder()
                .refName(refName)
                .displayName("Local Test")
                .emailDomain(tenantId)
                .databaseName(refName)
                .deploymentType(RealmDeploymentType.DEDICATED)
                .tenancyMode(RealmTenancyMode.SINGLE_TENANT)
                .domainContext(DomainContext.builder()
                        .tenantId(tenantId)
                        .orgRefName(orgRefName)
                        .defaultRealm(refName)
                        .accountId("0000000001")
                        .build())
                .build();
    }

    private static Realm shared(String refName) {
        return Realm.builder()
                .refName(refName)
                .displayName(refName)
                .emailDomain(refName)
                .databaseName(refName)
                .deploymentType(RealmDeploymentType.SHARED)
                .tenancyMode(RealmTenancyMode.MULTI_TENANT)
                .domainContext(DomainContext.builder()
                        .tenantId(refName)
                        .orgRefName(refName)
                        .defaultRealm(refName)
                        .accountId("0000000001")
                        .build())
                .build();
    }

    private static RealmTenantMembership membership(String tenantId, String orgRefName, String status) {
        return RealmTenantMembership.builder()
                .refName(tenantId)
                .displayName(tenantId)
                .realmRefName("helixor-digitalworker-P1")
                .tenantId(tenantId)
                .organizationRefName(orgRefName)
                .accountId("0000000001")
                .participationStatus(status)
                .build();
    }
}
