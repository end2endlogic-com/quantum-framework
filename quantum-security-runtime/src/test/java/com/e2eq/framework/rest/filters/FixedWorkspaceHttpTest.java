package com.e2eq.framework.rest.filters;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(FixedWorkspaceHttpTest.Profile.class)
class FixedWorkspaceHttpTest extends AuthorizationLifecycleHttpTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.security.session.selection-policy", "fixed-workspace",
                    "quantum.security.session.selection-policy-version", "1");
        }
    }
    @Test
    void fixedPolicyIgnoresRequestedWorkspaceAndRunsOnceAcrossAllFilters() throws Exception {
        int before = SelectionPolicyExamples.FixedWorkspace.calls.get();
        var result = call("alice", null, "unregistered");
        assertEquals(200, result.statusCode(), result.body());
        assertEquals("alice:tenant-a:app-a:editor", result.body());
        assertEquals(before + 1, SelectionPolicyExamples.FixedWorkspace.calls.get());
    }
}
