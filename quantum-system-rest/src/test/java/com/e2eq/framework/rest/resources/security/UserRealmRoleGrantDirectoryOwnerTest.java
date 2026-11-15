package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.rest.exceptions.ControlPlaneExceptionMapper;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.system.remote.ControlPlaneException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grant endpoints read and write the (user, realm) assignment at the directory owner, through
 * {@link RealmMembershipService}, never straight to this service's copy (the resource has no repo
 * here, so a direct write would fail). A refusal from a remote owner is reported, not retried locally.
 */
class UserRealmRoleGrantDirectoryOwnerTest {

    @Test
    void clearingAnApplicationGrantWritesAtTheDirectoryOwner() {
        UserRealmRole assignment = assignment();
        RecordingMembershipService owner = new RecordingMembershipService(assignment, null, null);

        Response response = resource(owner).clearApplicationGrant("pat", "acme-com");

        assertEquals(200, response.getStatus());
        assertEquals(1, owner.written.size());
        assertSame(assignment, owner.written.get(0));
        assertNull(assignment.getAuthorizedApplications());
    }

    @Test
    void anAssignmentTheOwnerDoesNotHoldIsNotFound() {
        RecordingMembershipService owner = new RecordingMembershipService(assignment(), null, null);

        Response response = resource(owner).clearApplicationGrant("pat", "other-com");

        assertEquals(404, response.getStatus());
        assertTrue(owner.written.isEmpty());
    }

    @Test
    void aRefusalFromTheRemoteOwnerOnAWriteIsReported() {
        RecordingMembershipService refusing = new RecordingMembershipService(assignment(), null,
            ControlPlaneException.rejected("upsert user realm role", 403, null));
        assertEquals(403, answered(() -> resource(refusing).clearApplicationGrant("pat", "acme-com")).getStatus());

        RecordingMembershipService unreachable = new RecordingMembershipService(assignment(), null,
            ControlPlaneException.unreachable("upsert user realm role", null));
        assertEquals(502, answered(() -> resource(unreachable).clearApplicationGrant("pat", "acme-com")).getStatus());
    }

    /**
     * Reads at the remote owner keep the owner's status too: a grant GET, and the first read of every
     * grant mutation, answer the owner's 4xx or a 502, never a 500, and nothing is written.
     */
    @Test
    void aFailureAtTheRemoteOwnerOnAReadKeepsItsStatus() {
        RecordingMembershipService refusing = new RecordingMembershipService(assignment(),
            ControlPlaneException.rejected("realms for user", 403, null), null);
        UserRealmRoleResource resource = resource(refusing);
        assertEquals(403, answered(() -> resource.getApplicationGrant("pat", "acme-com")).getStatus());
        assertEquals(403, answered(() -> resource.getTenantGrant("pat", "acme-com")).getStatus());
        assertEquals(403, answered(() -> resource.clearApplicationGrant("pat", "acme-com")).getStatus());
        assertEquals(403, answered(() -> resource.setApplicationGrant("pat", "acme-com", null)).getStatus());
        assertEquals(403, answered(() -> resource.setTenantGrant("pat", "acme-com", null)).getStatus());
        assertTrue(refusing.written.isEmpty());

        RecordingMembershipService down = new RecordingMembershipService(assignment(),
            ControlPlaneException.unreachable("realms for user", null), null);
        assertEquals(502, answered(() -> resource(down).getApplicationGrant("pat", "acme-com")).getStatus());
        RecordingMembershipService broken = new RecordingMembershipService(assignment(),
            ControlPlaneException.rejected("realms for user", 503, null), null);
        Response gateway = answered(() -> resource(broken).getTenantGrant("pat", "acme-com"));
        assertEquals(502, gateway.getStatus());
        assertEquals("REJECTED", ((RestError) gateway.getEntity()).getReasonMessage());
    }

    /**
     * What the client receives: the endpoint's response, or the response the application's
     * ControlPlaneException mapper gives for the failure it threw.
     */
    private static Response answered(java.util.function.Supplier<Response> call) {
        try {
            return call.get();
        } catch (ControlPlaneException failure) {
            return new ControlPlaneExceptionMapper().toResponse(failure);
        }
    }

    private static UserRealmRoleResource resource(RealmMembershipService owner) {
        UserRealmRoleResource resource = new UserRealmRoleResource(null);
        resource.realmMembershipService = owner;
        return resource;
    }

    private static UserRealmRole assignment() {
        UserRealmRole role = new UserRealmRole();
        role.setUserId("pat");
        role.setRealmRefName("acme-com");
        role.setAuthorizedApplications(List.of("planner"));
        role.setStatus(UserRealmRole.STATUS_SUSPENDED); // a grant is still managed on a suspended assignment
        return role;
    }

    private static final class RecordingMembershipService extends RealmMembershipService {
        private final UserRealmRole held;
        private final ControlPlaneException readFailure;
        private final ControlPlaneException failure;
        final List<UserRealmRole> written = new ArrayList<>();

        RecordingMembershipService(UserRealmRole held, ControlPlaneException readFailure,
                                   ControlPlaneException failure) {
            this.held = held;
            this.readFailure = readFailure;
            this.failure = failure;
        }

        @Override
        public List<UserRealmRole> realmsForUser(String userId) {
            if (readFailure != null) {
                throw readFailure;
            }
            return held.getUserId().equals(userId) ? List.of(held) : List.of();
        }

        @Override
        public UserRealmRole upsertUserRealmRole(UserRealmRole assignment) {
            if (failure != null) {
                throw failure;
            }
            written.add(assignment);
            return assignment;
        }
    }
}
