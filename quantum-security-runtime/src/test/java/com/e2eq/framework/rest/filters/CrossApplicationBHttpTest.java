package com.e2eq.framework.rest.filters;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;

@QuarkusTest
@TestProfile(CrossApplicationBHttpTest.Profile.class)
class CrossApplicationBHttpTest extends CrossApplicationIdentityHttpContract {
    protected String application() { return "app-b"; }
    public static class Profile implements QuarkusTestProfile {
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.security.session.application-id", "app-b",
                    "mp.jwt.verify.audiences", "shared-test-api");
        }
    }
}
