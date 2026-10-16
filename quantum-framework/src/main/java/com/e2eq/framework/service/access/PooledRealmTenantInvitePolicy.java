package com.e2eq.framework.service.access;

import com.e2eq.framework.model.auth.AccessibleTenantResolver;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.InviteScope;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
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

        UserRealmRole inviteeAssignment = acceptance.inviteeAssignment();
        requireReproduciblePartition(realm, invite, snapshot, inviteeAssignment);
        requireRolesStayWithinInviterTenants(realm, acceptance, tenantId, inviteeAssignment);

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

    /**
     * Only a MULTI_TENANT realm: that is where request tenant selection (SecurityFilter) enforces
     * the tenant grant this policy writes. A SHARED realm that is not MULTI_TENANT is refused.
     */
    private void requirePooled(Realm realm) {
        if (realm.getTenancyMode() != RealmTenancyMode.MULTI_TENANT) {
            throw new AccessInviteException(Code.INVITE_POLICY_UNAVAILABLE,
                "The pooled-realm invite policy only serves multi-tenant realms.");
        }
    }

    /**
     * The invitee must work in exactly the inviter's partition. Request tenant selection takes the
     * data segment, location and business transaction from the invitee's assignment default
     * partition only when it is for the same tenant/org/account, otherwise the defaults. An
     * existing member whose assignment would give it a different refinement than the snapshot
     * (for example a data segment the inviter is limited to) is refused rather than widened.
     */
    private void requireReproduciblePartition(Realm realm, AccessInvite invite, DataDomain snapshot, UserRealmRole inviteeAssignment) {
        if (inviteeAssignment == null) {
            return; // a new assignment takes the snapshot as its default partition
        }
        DataDomain existing = inviteeAssignment.getDataDomain();
        boolean sameCoordinate = existing != null
            && Objects.equals(snapshot.getTenantId(), existing.getTenantId())
            && Objects.equals(snapshot.getOrgRefName(), existing.getOrgRefName())
            && Objects.equals(snapshot.getAccountNum(), existing.getAccountNum());
        int segment = sameCoordinate ? existing.getDataSegment() : 0;
        String location = sameCoordinate ? existing.getLocationId() : null;
        String transaction = sameCoordinate ? existing.getBusinessTransactionId() : null;
        if (segment != snapshot.getDataSegment()
            || !Objects.equals(location, snapshot.getLocationId())
            || !Objects.equals(transaction, snapshot.getBusinessTransactionId())) {
            Log.warnf("access-invite.accept.refused reason=existing-member-partition-differs realm=%s invite=%s",
                realm.getRefName(), invite.getRefName());
            throw new AccessInviteException(Code.INVITE_SCOPE_UNRESOLVABLE,
                "Your existing access in this realm uses a different data partition than this invite. Ask a realm administrator to grant it.");
        }
    }

    /**
     * Roles are realm-scoped (one role list per user and realm), so roles an invite adds also apply
     * in every other tenant the invitee's assignment already authorizes. Adding a role is therefore
     * allowed only when the inviter is entitled to all of those tenants (or is a realm administrator
     * or the system principal); an assignment with a tenant pattern needs an administrator.
     */
    private void requireRolesStayWithinInviterTenants(Realm realm, InviteAcceptance acceptance, String tenantId, UserRealmRole inviteeAssignment) {
        AccessInvite invite = acceptance.invite();
        if (inviteeAssignment == null || invite.isIssuedBySystemPrincipal() || acceptance.inviterRealmAdministrator()) {
            return;
        }
        java.util.Set<String> held = new java.util.HashSet<>();
        if (inviteeAssignment.getRoles() != null) {
            inviteeAssignment.getRoles().stream().filter(Objects::nonNull).map(r -> r.trim().toLowerCase(java.util.Locale.ROOT)).forEach(held::add);
        }
        boolean addsRoles = invite.getGrantedRoles() != null && invite.getGrantedRoles().stream()
            .filter(Objects::nonNull)
            .map(r -> r.trim().toLowerCase(java.util.Locale.ROOT))
            .anyMatch(r -> !r.isEmpty() && !held.contains(r));
        if (!addsRoles) {
            return;
        }
        boolean pattern = inviteeAssignment.getAuthorizedTenantRegEx() != null && !inviteeAssignment.getAuthorizedTenantRegEx().isBlank();
        boolean entitled = !pattern;
        if (entitled && inviteeAssignment.getAuthorizedTenantIds() != null) {
            for (String other : inviteeAssignment.getAuthorizedTenantIds()) {
                if (other != null && !other.trim().equals(tenantId)
                    && !AccessibleTenantResolver.isAuthorized(acceptance.inviterAssignment(), other.trim())) {
                    entitled = false;
                    break;
                }
            }
        }
        if (!entitled) {
            Log.warnf("access-invite.accept.refused reason=roles-would-reach-tenants-outside-inviter realm=%s invite=%s",
                realm.getRefName(), invite.getRefName());
            throw new AccessInviteException(Code.INVITE_ROLE_NOT_GRANTABLE,
                "This invite's roles would also apply in tenants the inviter cannot grant. Ask a realm administrator.");
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
