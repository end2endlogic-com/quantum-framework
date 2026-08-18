package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.system.membership.RealmMembershipService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityFilterTenantSelectionTest {

    @Test
    void singleTenantRealmNeedsNoTenantHeader() {
        Realm realm = realm("dedicated", RealmTenancyMode.SINGLE_TENANT, "tenant-a");
        SecurityFilter filter = filter(realm, null, null);
        PrincipalContext context = context("dedicated", "tenant-a");

        assertSame(context, filter.applyTenantSelection(context, null));
        assertSame(context, filter.applyTenantSelection(context, "tenant-a"));
        assertThrows(ForbiddenException.class,
            () -> filter.applyTenantSelection(context, "tenant-b"));
    }

    @Test
    void multiTenantRealmRequiresTenantHeader() {
        Realm realm = realm("pooled", RealmTenancyMode.MULTI_TENANT, "tenant-a");
        SecurityFilter filter = filter(realm, assignment("pooled", "tenant-a"),
            membership("pooled", "tenant-a", "org-a", "account-a"));

        assertThrows(BadRequestException.class,
            () -> filter.applyTenantSelection(context("pooled", "tenant-a"), null));
    }

    @Test
    void multiTenantRealmBuildsDataDomainFromAuthorizedMembership() {
        Realm realm = realm("pooled", RealmTenancyMode.MULTI_TENANT, "tenant-a");
        SecurityFilter filter = filter(realm, assignment("pooled", "tenant-b"),
            membership("pooled", "tenant-b", "org-b", "account-b"));

        PrincipalContext selected = filter.applyTenantSelection(
            context("pooled", "tenant-a"), "tenant-b");

        assertEquals("pooled", selected.getDefaultRealm());
        assertEquals("tenant-b", selected.getDataDomain().getTenantId());
        assertEquals("org-b", selected.getDataDomain().getOrgRefName());
        assertEquals("account-b", selected.getDataDomain().getAccountNum());
        assertEquals("user@example.test", selected.getDataDomain().getOwnerId());
    }

    @Test
    void multiTenantRealmRejectsTenantOutsideUserAssignment() {
        Realm realm = realm("pooled", RealmTenancyMode.MULTI_TENANT, "tenant-a");
        SecurityFilter filter = filter(realm, assignment("pooled", "tenant-a"),
            membership("pooled", "tenant-b", "org-b", "account-b"));

        assertThrows(ForbiddenException.class,
            () -> filter.applyTenantSelection(context("pooled", "tenant-a"), "tenant-b"));
    }

    private static SecurityFilter filter(
            Realm realm, UserRealmRole assignment, RealmTenantMembership membership) {
        SecurityFilter filter = new SecurityFilter();
        filter.systemDirectory = new StubDirectory(Map.of(realm.getRefName(), realm));
        filter.realmMembershipService = new StubMembershipService(assignment, membership);
        return filter;
    }

    private static PrincipalContext context(String realm, String tenantId) {
        return new PrincipalContext.Builder()
            .withDefaultRealm(realm)
            .withDataDomain(DataDomain.builder()
                .tenantId(tenantId)
                .orgRefName("default-org")
                .accountNum("default-account")
                .ownerId("user@example.test")
                .build())
            .withUserId("user@example.test")
            .withRoles(new String[]{"user"})
            .withScope("AUTHENTICATED")
            .build();
    }

    private static Realm realm(String refName, RealmTenancyMode mode, String defaultTenantId) {
        return Realm.builder()
            .refName(refName)
            .emailDomain(refName + ".example.test")
            .databaseName(refName)
            .tenancyMode(mode)
            .domainContext(DomainContext.builder()
                .defaultRealm(refName)
                .tenantId(defaultTenantId)
                .orgRefName("default-org")
                .accountId("default-account")
                .build())
            .build();
    }

    private static UserRealmRole assignment(String realm, String... tenantIds) {
        return UserRealmRole.builder()
            .userId("user@example.test")
            .realmRefName(realm)
            .status(UserRealmRole.STATUS_ACTIVE)
            .authorizedTenantIds(List.of(tenantIds))
            .build();
    }

    private static RealmTenantMembership membership(
            String realm, String tenantId, String org, String account) {
        return RealmTenantMembership.builder()
            .realmRefName(realm)
            .tenantId(tenantId)
            .organizationRefName(org)
            .accountId(account)
            .participationStatus(RealmTenantMembership.PARTICIPATION_STATUS_ACTIVE)
            .build();
    }

    private record StubDirectory(Map<String, Realm> realms) implements SystemDirectory {
        @Override public String systemRealmId() { return "system"; }
        @Override public Optional<Realm> findRealmByEmailDomain(String emailDomain) { return Optional.empty(); }
        @Override public Optional<Realm> findRealmByRefName(String refName) {
            return Optional.ofNullable(realms.get(refName));
        }
        @Override public Realm registerRealm(Realm realm) { throw new UnsupportedOperationException(); }
        @Override public Optional<CredentialUserIdPassword> findCredentialBySubject(String subject) {
            return Optional.empty();
        }
        @Override public Optional<CredentialUserIdPassword> findCredentialByUserId(String userId) {
            return Optional.empty();
        }
    }

    private static final class StubMembershipService extends RealmMembershipService {
        private final UserRealmRole assignment;
        private final RealmTenantMembership membership;

        private StubMembershipService(UserRealmRole assignment, RealmTenantMembership membership) {
            this.assignment = assignment;
            this.membership = membership;
        }

        @Override
        public Optional<UserRealmRole> assignmentForUser(String userId, String realmRefName) {
            return Optional.ofNullable(assignment);
        }

        @Override
        public Optional<RealmTenantMembership> tenantInRealm(String realmRefName, String tenantId) {
            return Optional.ofNullable(membership)
                .filter(value -> tenantId.equals(value.getTenantId()));
        }
    }
}
