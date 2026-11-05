package com.e2eq.framework.rest.filters;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;

@QuarkusTest
@TestProfile(CrossApplicationAHttpTest.Profile.class)
class CrossApplicationAHttpTest extends CrossApplicationIdentityHttpContract {
    protected String application() { return "app-a"; }
    public static class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.security.session.application-id", "app-a",
                    "mp.jwt.verify.audiences", "shared-test-api");
        }
    }
}
