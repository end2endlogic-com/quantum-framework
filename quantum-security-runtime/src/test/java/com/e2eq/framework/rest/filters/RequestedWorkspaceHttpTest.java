package com.e2eq.framework.rest.filters;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RequestedWorkspaceHttpTest.Profile.class)
class RequestedWorkspaceHttpTest extends AuthorizationLifecycleHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.security.session.selection-policy", "requested-workspace",
                    "quantum.security.session.selection-policy-version", "1");
        }
    }
    @Test
    void policyCannotAdmitAnUnregisteredRealm() throws Exception {
        // A role alone must not substitute for realm admission.
        var assignment = new com.e2eq.framework.model.security.UserRealmRole();
        assignment.setRoles(java.util.List.of("editor"));
        org.mockito.Mockito.when(memberships.assignmentForUser("alice", "unregistered"))
                .thenReturn(java.util.Optional.of(assignment));
        var result = call("alice", null, "unregistered");
        assertEquals(403, result.statusCode(), result.body());
        // Refused typed before any lookup: the unregistered realm's role is never read.
        assertTrue(result.body().contains("REALM_NOT_AUTHORIZED"), result.body());
        var typed = call("alice", null, "unregistered", "lifecycle/self");
        assertEquals(403, typed.statusCode(), typed.body());
        assertTrue(typed.body().contains("REALM_NOT_AUTHORIZED"), typed.body());
        org.mockito.Mockito.verify(memberships, org.mockito.Mockito.never()).assignmentForUser("alice", "unregistered");
    }
}
