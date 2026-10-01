package com.e2eq.framework.system.membership;

import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.remote.ControlPlaneException;
import com.e2eq.framework.system.remote.RemoteMembershipClient;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ProcessingException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remote mode (QUANTUM_MODE=remote): membership and user realm-role writes go
 * through the generated control-plane client, never the local repos, and every
 * failure surfaces as a typed {@link ControlPlaneException}.
 */
class RealmMembershipServiceRemoteWriteTest {

    private static class StubEndpoint implements DefaultEndpoint {
        final AtomicReference<String[]> rolePath = new AtomicReference<>();
        final AtomicReference<UserRealmRoleEntry> roleBody = new AtomicReference<>();
        final AtomicReference<String[]> membershipPath = new AtomicReference<>();
        RuntimeException failure;

        @Override public RealmCatalogEntry findRealmByEmailDomain(String e) { return null; }
        @Override public RealmCatalogEntry findRealmByRefName(String r) { return null; }
        @Override public RealmCatalogEntry registerRealm(String r, RealmCatalogEntry b) { return b; }
        @Override public List<RealmMembershipEntry> membersOfRealm(String r) { return List.of(); }
        @Override public List<UserRealmRoleEntry> realmsForUser(String u) { return List.of(); }

        @Override
        public RealmMembershipEntry upsertRealmMembership(String r, String o, RealmMembershipEntry b) {
            if (failure != null) {
                throw failure;
            }
            membershipPath.set(new String[] {r, o});
            return b;
        }

        @Override
        public UserRealmRoleEntry upsertUserRealmRole(String u, String r, UserRealmRoleEntry b) {
            if (failure != null) {
                throw failure;
            }
            rolePath.set(new String[] {u, r});
            roleBody.set(b);
            return b;
        }
    }

    private static RealmMembershipService remoteService(StubEndpoint endpoint) {
        RealmMembershipService service = new RealmMembershipService() {
            @Override
            RemoteMembershipClient remote() {
                return new RemoteMembershipClient(endpoint);
            }
        };
        service.quantumModeConfig = QuantumModeConfig.of("remote", Optional.of("http://quantum-system:8080"));
        service.userRealmRoleRepo = new UserRealmRoleRepo() {
            @Override
            public UserRealmRole save(String realmId, UserRealmRole value) {
                throw new AssertionError("remote mode must not write the local user realm-role repo");
            }
        };
        return service;
    }

    private static UserRealmRole serviceAssignment() {
        return UserRealmRole.builder()
            .refName("service-realm-role:8f0c:quantum-auth")
            .userId("8f0c")
            .subject("8f0c")
            .realmRefName("quantum-auth")
            .roles(List.of("registration-intake"))
            .authorizedApplications(List.of("helixor-licensing"))
            .authorizedTenantIds(List.of())
            .status(UserRealmRole.STATUS_ACTIVE)
            .build();
    }

    @Test
    void remoteUserRealmRoleWriteGoesThroughGeneratedContract() {
        StubEndpoint endpoint = new StubEndpoint();

        UserRealmRole saved = remoteService(endpoint).upsertUserRealmRole(serviceAssignment());

        assertEquals("8f0c", endpoint.rolePath.get()[0]);
        assertEquals("quantum-auth", endpoint.rolePath.get()[1]);
        assertEquals(List.of("registration-intake"), endpoint.roleBody.get().getRoles());
        assertEquals(List.of("helixor-licensing"), endpoint.roleBody.get().getAuthorizedApplications());
        assertEquals(UserRealmRole.STATUS_ACTIVE, endpoint.roleBody.get().getStatus());
        assertEquals("8f0c", saved.getUserId());
        assertEquals("8f0c", saved.getSubject());
        assertEquals(List.of("registration-intake"), saved.getRoles());
    }

    @Test
    void remoteMembershipWriteGoesThroughGeneratedContract() {
        StubEndpoint endpoint = new StubEndpoint();
        RealmTenantMembership membership = new RealmTenantMembership();
        membership.setRealmRefName("engineering");
        membership.setOrganizationRefName("helixor-ai");
        membership.setMembershipRole(RealmTenantMembership.MEMBERSHIP_ROLE_OWNER);

        RealmTenantMembership saved = remoteService(endpoint).upsertMembership(membership);

        assertEquals("engineering", endpoint.membershipPath.get()[0]);
        assertEquals("helixor-ai", endpoint.membershipPath.get()[1]);
        assertEquals(RealmTenantMembership.MEMBERSHIP_ROLE_OWNER, saved.getMembershipRole());
    }

    @Test
    void controlPlaneRefusalIsTypedRejected() {
        StubEndpoint endpoint = new StubEndpoint();
        endpoint.failure = new ForbiddenException();

        ControlPlaneException error = assertThrows(ControlPlaneException.class,
            () -> remoteService(endpoint).upsertUserRealmRole(serviceAssignment()));

        assertSame(ControlPlaneException.Kind.REJECTED, error.kind());
        assertEquals(403, error.httpStatus().orElseThrow());
        assertTrue(error.operation().contains("quantum-auth"));
    }

    @Test
    void controlPlaneOutageIsTypedUnreachable() {
        StubEndpoint endpoint = new StubEndpoint();
        endpoint.failure = new ProcessingException("connection refused");

        ControlPlaneException error = assertThrows(ControlPlaneException.class,
            () -> remoteService(endpoint).upsertUserRealmRole(serviceAssignment()));

        assertSame(ControlPlaneException.Kind.UNREACHABLE, error.kind());
        assertTrue(error.getMessage().contains("no local fallback"));
    }

    @Test
    void mismatchedResponseIdentityIsContractViolation() {
        StubEndpoint endpoint = new StubEndpoint() {
            @Override
            public UserRealmRoleEntry upsertUserRealmRole(String u, String r, UserRealmRoleEntry b) {
                b.setUserId("someone-else");
                return b;
            }
        };

        ControlPlaneException error = assertThrows(ControlPlaneException.class,
            () -> remoteService(endpoint).upsertUserRealmRole(serviceAssignment()));

        assertSame(ControlPlaneException.Kind.CONTRACT_VIOLATION, error.kind());
    }
}
