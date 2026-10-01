package com.e2eq.framework.system.remote;

import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase C (2/2): membership/role resolution over the control-plane API,
 * a pure mapper over the SDK-generated client {@link DefaultEndpoint}
 * (members/{realm}, users/{id}/realms, and their PUT write operations). Same posture as
 * RemoteSystemDirectory: optional service bearer (on the client), strict
 * fail-loud, no local fallback. Maps the generated DTOs to the framework
 * persistence types.
 */
public class RemoteMembershipClient {

    private final DefaultEndpoint client;

    public RemoteMembershipClient(String baseUrl, Optional<String> bearerToken) {
        this(ControlPlaneClientFactory.build(baseUrl, bearerToken));
    }

    public RemoteMembershipClient(DefaultEndpoint client) {
        this.client = client;
    }

    public List<RealmTenantMembership> membersOfRealm(String realmRefName) {
        List<RealmMembershipEntry> entries = call(() -> client.membersOfRealm(realmRefName),
            "members of realm " + realmRefName);
        List<RealmTenantMembership> members = new ArrayList<>();
        for (RealmMembershipEntry entry : entries) {
            RealmTenantMembership membership = new RealmTenantMembership();
            membership.setRealmRefName(entry.getRealmRefName());
            membership.setOrganizationRefName(entry.getOrganizationRefName());
            membership.setAccountId(entry.getAccountId());
            membership.setTenantId(entry.getTenantId());
            membership.setMembershipRole(Optional.ofNullable(entry.getMembershipRole())
                .orElse(RealmTenantMembership.MEMBERSHIP_ROLE_OWNER));
            membership.setParticipationStatus(entry.getParticipationStatus());
            members.add(membership);
        }
        return members;
    }

    public List<UserRealmRole> realmsForUser(String userId) {
        List<UserRealmRoleEntry> entries = call(() -> client.realmsForUser(userId),
            "realms for user " + userId);
        List<UserRealmRole> assignments = new ArrayList<>();
        for (UserRealmRoleEntry entry : entries) {
            UserRealmRole assignment = new UserRealmRole();
            assignment.setUserId(entry.getUserId());
            assignment.setRealmRefName(entry.getRealmRefName());
            assignment.setSponsoringOrgRefName(entry.getSponsoringOrgRefName());
            assignment.setStatus(entry.getStatus());
            assignment.setRoles(entry.getRoles() == null ? List.of() : new ArrayList<>(entry.getRoles()));
            assignment.setAuthorizedApplications(entry.getAuthorizedApplications());
            assignment.setDefaultApplication(entry.getDefaultApplication());
            assignment.setAuthorizedTenantIds(entry.getAuthorizedTenantIds());
            assignments.add(assignment);
        }
        return assignments;
    }

    /** Create or update an org/account membership on the owning control plane. */
    public RealmTenantMembership upsertRealmMembership(RealmTenantMembership membership) {
        String what = "upsert membership " + membership.getOrganizationRefName()
            + " in realm " + membership.getRealmRefName();
        RealmMembershipEntry saved = call(() -> client.upsertRealmMembership(
                membership.getRealmRefName(),
                membership.getOrganizationRefName(),
                ControlPlaneRealmMapper.toEntry(membership)),
            what);
        if (saved == null) {
            throw ControlPlaneException.contractViolation(what, "empty response body");
        }
        return ControlPlaneRealmMapper.fromEntry(saved);
    }

    /** Create or update a user's role assignment on the owning control plane. */
    public UserRealmRole upsertUserRealmRole(UserRealmRole assignment) {
        String what = "upsert realm role for user " + assignment.getUserId()
            + " in realm " + assignment.getRealmRefName();
        UserRealmRoleEntry entry = ControlPlaneRealmMapper.toEntry(assignment);
        if (entry.getRoles() == null) {
            // roles is required by the contract; an assignment without roles is an empty grant.
            entry.setRoles(List.of());
        }
        UserRealmRoleEntry saved = call(() -> client.upsertUserRealmRole(
                assignment.getUserId(), assignment.getRealmRefName(), entry),
            what);
        if (saved == null) {
            throw ControlPlaneException.contractViolation(what, "empty response body");
        }
        if (!assignment.getUserId().equals(saved.getUserId())
                || !assignment.getRealmRefName().equals(saved.getRealmRefName())) {
            throw ControlPlaneException.contractViolation(what,
                "response identity " + saved.getUserId() + "/" + saved.getRealmRefName()
                    + " does not match the request");
        }
        return ControlPlaneRealmMapper.fromEntry(saved);
    }

    private <T> T call(java.util.function.Supplier<T> supplier, String what) {
        try {
            return supplier.get();
        } catch (WebApplicationException e) {
            throw ControlPlaneException.rejected(what, e.getResponse().getStatus(), e);
        } catch (ProcessingException e) {
            throw ControlPlaneException.unreachable(what, e);
        }
    }
}
