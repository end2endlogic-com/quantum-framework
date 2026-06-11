package com.e2eq.framework.system.membership;

import com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Control-plane membership resolution (B4 identity model; realm-membership
 * ADR: realm = placement + lifecycle, DataDomain = visibility, orgs/accounts
 * are MEMBERS of realms with exactly one owner).
 *
 * Read side of the routing registry: the IdP consults {@link #rolesForUser}
 * at token issuance to mint per-realm role claims; the tenant plane consults
 * memberships to route a principal to a realm datastore. Embedded mode reads
 * the system realm directly; remote mode (Phase C) gets this same interface
 * backed by the control-plane HTTP client.
 */
@ApplicationScoped
public class RealmMembershipService {

    @Inject
    RealmTenantMembershipRepo membershipRepo;

    @Inject
    UserRealmRoleRepo userRealmRoleRepo;

    /** All realm memberships for an org (owner and participant alike). */
    public List<RealmTenantMembership> membershipsForOrg(String orgRefName) {
        return membershipRepo.getListByQuery(0, -1,
            "organizationRefName:" + orgRefName);
    }

    /** Every org/account participating in a realm (the collaboration roster). */
    public List<RealmTenantMembership> membersOfRealm(String realmRefName) {
        return membershipRepo.getListByQuery(0, -1,
            "realmRefName:" + realmRefName);
    }

    /** The single owner membership of a realm (lifecycle/billing authority). */
    public Optional<RealmTenantMembership> ownerOfRealm(String realmRefName) {
        return membershipRepo.getListByQuery(0, 2,
                "realmRefName:" + realmRefName
                + "&&membershipRole:" + RealmTenantMembership.MEMBERSHIP_ROLE_OWNER)
            .stream().findFirst();
    }

    /** A user's per-realm role assignments (the GitHub-model membership list). */
    public List<UserRealmRole> realmsForUser(String userId) {
        return userRealmRoleRepo.getListByQuery(0, -1, "userId:" + userId);
    }

    /** The user's roles within one realm; empty when not a member. */
    public List<String> rolesForUser(String userId, String realmRefName) {
        return userRealmRoleRepo.getListByQuery(0, 1,
                "userId:" + userId + "&&realmRefName:" + realmRefName)
            .stream()
            .filter(a -> !UserRealmRole.STATUS_SUSPENDED.equals(a.getStatus()))
            .findFirst()
            .map(UserRealmRole::getRoles)
            .orElse(List.of());
    }
}
