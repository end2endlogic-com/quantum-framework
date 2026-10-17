package com.e2eq.framework.system.remote;

import com.e2eq.framework.api.system.SubjectFlatRoles;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.api.IdentityEndpoint;
import com.e2eq.framework.controlplane.model.SubjectFlatRolesEntry;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Remote-mode flat roles: RemoteSystemDirectory over the generated {@link IdentityEndpoint}
 * (control-plane contract 1.1.0 flatRolesForSubject). The HTTP binding of the generated client
 * is exercised end to end against the real resource in quantum-auth-service (quantum-enterprise),
 * where the MicroProfile Rest Client runtime is present.
 */
class RemoteSystemDirectoryFlatRolesTest {

    private static final String SUB = "subject-123";

    private static SubjectFlatRolesEntry entry(String subject, String userId, boolean active, String... roles) {
        SubjectFlatRolesEntry e = new SubjectFlatRolesEntry();
        e.setSubject(subject);
        e.setUserId(userId);
        e.setActive(active);
        e.setRoles(List.of(roles));
        return e;
    }

    private static RemoteSystemDirectory directory(IdentityEndpoint identity, Duration ttl, AtomicLong clock) {
        return new RemoteSystemDirectory(mock(DefaultEndpoint.class), identity, ttl, clock::get);
    }

    @Test
    void mapsFlatRolesAndCachesThemForTheTtl() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        RemoteSystemDirectory directory = directory(subject -> {
            calls.incrementAndGet();
            return entry(subject, "user@example.test", true, "user", " analyst ");
        }, Duration.ofSeconds(30), clock);

        SubjectFlatRoles flat = directory.flatRolesForSubject(SUB).orElseThrow();
        assertEquals("user@example.test", flat.userId());
        assertTrue(flat.active());
        assertEquals(Set.of("user", "analyst"), flat.roles());

        clock.addAndGet(Duration.ofSeconds(29).toNanos());
        directory.flatRolesForSubject(SUB);
        assertEquals(1, calls.get(), "served from cache inside the TTL");

        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        directory.flatRolesForSubject(SUB);
        assertEquals(2, calls.get(), "revocation takes effect once the TTL expires");
    }

    @Test
    void zeroTtlDisablesCaching() {
        AtomicInteger calls = new AtomicInteger();
        RemoteSystemDirectory directory = directory(subject -> {
            calls.incrementAndGet();
            return entry(subject, "u", true, "user");
        }, Duration.ZERO, new AtomicLong());
        directory.flatRolesForSubject(SUB);
        directory.flatRolesForSubject(SUB);
        assertEquals(2, calls.get());
    }

    @Test
    void unknownSubjectIsEmpty() {
        RemoteSystemDirectory directory = directory(subject -> {
            throw new WebApplicationException(Response.status(404).build());
        }, Duration.ofSeconds(30), new AtomicLong());
        assertEquals(Optional.empty(), directory.flatRolesForSubject(SUB));
    }

    @Test
    void disabledCredentialHasNoRoles() {
        RemoteSystemDirectory directory = directory(subject -> entry(subject, "u", false),
            Duration.ofSeconds(30), new AtomicLong());
        SubjectFlatRoles flat = directory.flatRolesForSubject(SUB).orElseThrow();
        assertFalse(flat.active());
        assertTrue(flat.roles().isEmpty());
    }

    @Test
    void unreachableFailsLoudAndIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        RemoteSystemDirectory directory = directory(subject -> {
            calls.incrementAndGet();
            throw new ProcessingException("connection refused");
        }, Duration.ofSeconds(30), new AtomicLong());
        ControlPlaneException e = assertThrows(ControlPlaneException.class,
            () -> directory.flatRolesForSubject(SUB));
        assertEquals(ControlPlaneException.Kind.UNREACHABLE, e.kind());
        assertThrows(ControlPlaneException.class, () -> directory.flatRolesForSubject(SUB));
        assertEquals(2, calls.get(), "failures are never cached");
    }

    @Test
    void refusalsAreTypedFailures() {
        for (int status : new int[]{401, 403, 501, 503}) {
            RemoteSystemDirectory directory = directory(subject -> {
                throw new WebApplicationException(Response.status(status).build());
            }, Duration.ofSeconds(30), new AtomicLong());
            ControlPlaneException e = assertThrows(ControlPlaneException.class,
                () -> directory.flatRolesForSubject(SUB));
            assertEquals(ControlPlaneException.Kind.REJECTED, e.kind());
            assertEquals(status, e.httpStatus().orElseThrow());
        }
    }

    @Test
    void responseForAnotherSubjectIsAContractViolation() {
        RemoteSystemDirectory directory = directory(subject -> entry("someone-else", "u", true, "admin"),
            Duration.ofSeconds(30), new AtomicLong());
        ControlPlaneException e = assertThrows(ControlPlaneException.class,
            () -> directory.flatRolesForSubject(SUB));
        assertEquals(ControlPlaneException.Kind.CONTRACT_VIOLATION, e.kind());
    }

    @Test
    void disabledCredentialWithRolesIsAContractViolation() {
        RemoteSystemDirectory directory = directory(subject -> entry(subject, "u", false, "admin"),
            Duration.ofSeconds(30), new AtomicLong());
        assertThrows(ControlPlaneException.class, () -> directory.flatRolesForSubject(SUB));
    }

    @Test
    void missingIdentityClientFailsLoud() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(mock(DefaultEndpoint.class));
        ControlPlaneException e = assertThrows(ControlPlaneException.class,
            () -> directory.flatRolesForSubject(SUB));
        assertEquals(ControlPlaneException.Kind.NOT_CONFIGURED, e.kind());
    }

    @Test
    void withoutServiceTokenItNeverForwardsTheUsersBearer() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory("http://127.0.0.1:9",
            () -> Optional.of("end-user-token"), null, Optional::empty, Duration.ofSeconds(30));
        ControlPlaneException e = assertThrows(ControlPlaneException.class,
            () -> directory.flatRolesForSubject(SUB));
        assertEquals(ControlPlaneException.Kind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("quantum.system-service.token"));
    }

    @Test
    void localProjectionAppliesTheSameDisabledRule() {
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setSubject(SUB);
        credential.setUserId("u");
        credential.setRoles(new String[]{"admin", " ", null});
        assertEquals(Set.of("admin"), SubjectFlatRoles.fromCredential(credential).roles());
        credential.setActiveStatus(ActiveStatus.INACTIVE);
        assertTrue(SubjectFlatRoles.fromCredential(credential).roles().isEmpty());
        credential.setActiveStatus(ActiveStatus.DELETED);
        assertFalse(SubjectFlatRoles.fromCredential(credential).active());
    }
}
