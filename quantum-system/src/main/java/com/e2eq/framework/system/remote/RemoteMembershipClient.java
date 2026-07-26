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
 * (members/{realm}, users/{id}/realms). Same posture as
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
            assignments.add(assignment);
        }
        return assignments;
    }

    private <T> T call(java.util.function.Supplier<T> supplier, String what) {
        try {
            return supplier.get();
        } catch (WebApplicationException e) {
            throw new IllegalStateException("Control plane returned HTTP "
                + e.getResponse().getStatus() + " for " + what, e);
        } catch (ProcessingException e) {
            throw new IllegalStateException("Control plane unreachable for " + what
                + " — failing loud, no local fallback.", e);
        }
    }
}
