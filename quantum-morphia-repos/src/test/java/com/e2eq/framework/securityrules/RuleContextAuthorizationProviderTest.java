package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.AuthorizationProvider;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.EvalMode;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RuleContextAuthorizationProviderTest {

    @Test
    void configuredAuthorizationProviderOwnsTheDecision() {
        AtomicInteger calls = new AtomicInteger();
        SecurityCheckResponse expected = new SecurityCheckResponse();
        expected.setFinalEffect(RuleEffect.ALLOW);
        expected.setDecision("ALLOW");

        AuthorizationProvider provider = new StubAuthorizationProvider(calls, expected);
        RuleContext ruleContext = new RuleContext();
        ruleContext.authProviderFactory = new StubAuthProviderFactory(provider);

        DataDomain domain = new DataDomain("acme", "acme", "acme", 0, "alice@example.com");
        PrincipalContext principal = new PrincipalContext.Builder()
                .withDefaultRealm("acme")
                .withApplicationId("scheduler")
                .withDataDomain(domain)
                .withUserId("alice@example.com")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .build();
        ResourceContext resource = new ResourceContext.Builder()
                .withRealm("acme")
                .withApplicationId("scheduler")
                .withArea("scheduler")
                .withFunctionalDomain("shift")
                .withAction("create")
                .withDataDomain(domain)
                .build();

        SecurityCheckResponse actual = ruleContext.checkRules(principal, resource);

        assertSame(expected, actual);
        assertEquals(1, calls.get());
    }

    private static final class StubAuthProviderFactory extends AuthProviderFactory {
        private final AuthProvider provider;

        private StubAuthProviderFactory(AuthProvider provider) {
            this.provider = provider;
        }

        @Override
        public AuthProvider getAuthProvider() {
            return provider;
        }
    }

    private static final class StubAuthorizationProvider implements AuthorizationProvider {
        private final AtomicInteger calls;
        private final SecurityCheckResponse response;

        private StubAuthorizationProvider(AtomicInteger calls, SecurityCheckResponse response) {
            this.calls = calls;
            this.response = response;
        }

        @Override
        public SecurityCheckResponse checkRules(
                PrincipalContext principalContext,
                ResourceContext resourceContext,
                String modelClass,
                Object resourceInstance,
                RuleEffect defaultFinalEffect,
                EvalMode evalMode) {
            calls.incrementAndGet();
            return response;
        }

        @Override
        public SecurityIdentity validateAccessToken(String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return "stub-authorization";
        }

        @Override
        public LoginResponse login(String userId, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoginResponse refreshTokens(String refreshToken) {
            throw new UnsupportedOperationException();
        }
    }
}
