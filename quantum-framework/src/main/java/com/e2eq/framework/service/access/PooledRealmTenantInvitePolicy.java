package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AccessibleTenantResolver;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.InviteScope;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import com.e2eq.framework.system.membership.RealmMembershipService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;

/**
 * Policy for pooled realms (several tenants in one realm, separated by DataDomain): the invitee
 * joins the inviter's partition.
 *
 * <ul>
 *   <li>The partition is the invite's scope snapshot: the inviter's resolved DataDomain from its
 *       verified principal. Every field is kept (tenant, org, account, data segment, location,
 *       business transaction) except {@code ownerId}, which becomes the invitee.</li>
 *   <li>The tenant must be an active member of the realm and the partition's org/account must be
 *       that membership's (the pooled-realm tenant selection builds the request partition from the
 *       membership, so any other org/account could not be reproduced for the invitee).</li>
 *   <li>A partition that adds a DataDomainPolicy filter or facet filters is refused: the invitee
 *       would not carry those constraints and would reach more than the inviter.</li>
 *   <li>The inviter must be entitled to the tenant: its realm assignment authorizes it, or it is a
 *       realm administrator (or the system principal). Checked at creation and again at acceptance.</li>
 *   <li>Grant: a new account's home context is the partition; a new {@code UserRealmRole}'s data
 *       domain is the partition; the tenant is unioned into {@code authorizedTenantIds} (an existing
 *       member keeps its other tenants and its default partition); roles stay realm-scoped.</li>
 * </ul>
 */
@ApplicationScoped
public class PooledRealmTenantInvitePolicy implements InviteAcceptancePolicy {

    public static final String ID = "pooled-realm-tenant";

    @Inject
    RealmMembershipService realmMembershipService;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validateCreate(InviteCreation creation) {
        Realm realm = creation.realm();
        requirePooled(realm);
        DataDomain partition = requireCarriablePartition(realm, creation.scope());
        boolean entitled = creation.systemPrincipal()
            || creation.realmAdministrator()
            || AccessibleTenantResolver.isAuthorized(creation.inviterAssignment(), partition.getTenantId());
        if (!entitled) {
            Log.warnf("access-invite.create.refused reason=tenant-not-authorized realm=%s tenant=%s inviter=%s",
                realm.getRefName(), partition.getTenantId(), creation.inviterUserId());
            throw new AccessInviteException(Code.INVITE_SCOPE_NOT_AUTHORIZED, "You cannot invite users into this tenant.");
        }
    }

    @Override
    public DataDomain inviteRecordDataDomain(InviteCreation creation) {
        return copy(creation.scope().getDataDomain(), creation.inviterUserId());
    }

    @Override
    public InviteGrant resolveGrant(InviteAcceptance acceptance) {
        Realm realm = acceptance.realm();
        AccessInvite invite = acceptance.invite();
        requirePooled(realm);
        DataDomain snapshot = requireCarriablePartition(realm, invite.getScope());
        String tenantId = snapshot.getTenantId();
        UserRealmRole inviterAssignment = acceptance.inviterAssignment();
        boolean entitled = invite.isIssuedBySystemPrincipal()
            || acceptance.inviterRealmAdministrator()
            || AccessibleTenantResolver.isAuthorized(inviterAssignment, tenantId);
        if (!entitled) {
            Log.warnf("access-invite.accept.refused reason=inviter-tenant-not-authorized realm=%s invite=%s tenant=%s inviter=%s",
                realm.getRefName(), invite.getRefName(), tenantId, invite.getInvitedByUserId());
            throw new AccessInviteException(Code.INVITE_SCOPE_NOT_AUTHORIZED,
                "The inviter can no longer grant access to this tenant. Ask for a new invite.");
        }

        DataDomain partition = copy(snapshot, acceptance.inviteeUserId());
        DomainContext home = DomainContext.builder()
            .tenantId(tenantId)
            .defaultRealm(realm.getRefName())
            .orgRefName(partition.getOrgRefName())
            .accountId(partition.getAccountNum())
            .dataSegment(partition.getDataSegment())
            .build();
        return InviteGrant.builder()
            .homeContext(home)
            .assignmentDataDomain(partition)
            .newAssignmentTenantIds(List.of(tenantId))
            .addTenantIds(List.of(tenantId))
            .sponsoringOrgRefName(partition.getOrgRefName())
            .roles(invite.getGrantedRoles())
            .authorizedApplications(invite.getAuthorizedApplications())
            .profileDataDomain(copy(partition, acceptance.inviteeUserId()))
            .tenantId(tenantId)
            .build();
    }

    private void requirePooled(Realm realm) {
        if (!InviteAcceptancePolicies.pooled(realm)) {
            throw new AccessInviteException(Code.INVITE_POLICY_UNAVAILABLE,
                "The pooled-realm invite policy only serves realms that hold several tenants.");
        }
    }

    /**
     * The scope's partition, when this policy can place an invitee in it exactly; otherwise a typed
     * refusal. Never substitutes a default field.
     */
    private DataDomain requireCarriablePartition(Realm realm, InviteScope scope) {
        DataDomain partition = scope == null ? null : scope.getDataDomain();
        if (partition == null || blank(partition.getTenantId())) {
            throw unresolvable(realm, "no-partition", "The inviter's data partition could not be resolved for this realm.");
        }
        if (scope.getRealmRefName() == null || !scope.getRealmRefName().equalsIgnoreCase(realm.getRefName())) {
            throw unresolvable(realm, "partition-from-another-realm", "The inviter's data partition was not resolved in this realm.");
        }
        if (scope.hasPolicyConstraints()) {
            throw unresolvable(realm, "partition-has-policy-filter",
                "The inviter's data partition carries policy filters an invite cannot pass on.");
        }
        RealmTenantMembership membership = realmMembershipService.tenantInRealm(realm.getRefName(), partition.getTenantId())
            .orElseThrow(() -> unresolvable(realm, "tenant-not-active-member", "The tenant is not an active member of this realm."));
        if (!Objects.equals(membership.getOrganizationRefName(), partition.getOrgRefName())
            || !Objects.equals(membership.getAccountId(), partition.getAccountNum())) {
            throw unresolvable(realm, "partition-org-account-not-membership",
                "The inviter's data partition names an organization or account the tenant's realm membership does not.");
        }
        return partition;
    }

    private static AccessInviteException unresolvable(Realm realm, String reason, String message) {
        Log.warnf("access-invite.scope.unresolvable reason=%s realm=%s", reason, realm.getRefName());
        return new AccessInviteException(Code.INVITE_SCOPE_UNRESOLVABLE, message);
    }

    /** A copy of {@code source} owned by {@code ownerId}; every other field is kept as resolved. */
    static DataDomain copy(DataDomain source, String ownerId) {
        DataDomain copy = DataDomain.builder()
            .orgRefName(source.getOrgRefName())
            .accountNum(source.getAccountNum())
            .tenantId(source.getTenantId())
            .dataSegment(source.getDataSegment())
            .ownerId(ownerId)
            .build();
        copy.setLocationId(source.getLocationId());
        copy.setBusinessTransactionId(source.getBusinessTransactionId());
        return copy;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
