package com.e2eq.framework.rest.services;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.rest.models.ResetPasswordRequest;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthCredentialServiceTest {

    @Test
    void explicitProviderWins() {
        TestAuthProviderFactory factory = new TestAuthProviderFactory();
        TestUserManager defaultManager = new TestUserManager("custom", "https://custom.example.com");
        TestUserManager oidcManager = new TestUserManager("oidc", "https://oidc.example.com");
        factory.defaultManager = defaultManager;
        factory.managers.put("oidc", oidcManager);

        AuthCredentialService service = service(factory, Optional.empty());

        assertSame(oidcManager, service.resolveUserManager("alice", "oidc"));
    }

    @Test
    void credentialIssuerRoutesToMatchingProvider() {
        TestAuthProviderFactory factory = new TestAuthProviderFactory();
        TestUserManager defaultManager = new TestUserManager("custom", "https://custom.example.com");
        TestUserManager oidcManager = new TestUserManager("oidc", "https://oidc.example.com");
        factory.defaultManager = defaultManager;
        factory.issuers.put("https://oidc.example.com", oidcManager);

        CredentialUserIdPassword credential = credential("alice");
        credential.setIssuer("https://oidc.example.com");
        AuthCredentialService service = service(factory, Optional.of(credential));

        assertSame(oidcManager, service.resolveUserManager("alice", null));
    }

    @Test
    void credentialAuthProviderNameRoutesToNamedProvider() {
        TestAuthProviderFactory factory = new TestAuthProviderFactory();
        TestUserManager defaultManager = new TestUserManager("custom", "https://custom.example.com");
        TestUserManager oidcManager = new TestUserManager("oidc", "https://oidc.example.com");
        factory.defaultManager = defaultManager;
        factory.managers.put("oidc", oidcManager);

        CredentialUserIdPassword credential = credential("alice");
        credential.setAuthProviderName("oidc");
        AuthCredentialService service = service(factory, Optional.of(credential));

        assertSame(oidcManager, service.resolveUserManager("alice", null));
    }

    @Test
    void nonPasswordCredentialFallsBackToDefaultProvider() {
        TestAuthProviderFactory factory = new TestAuthProviderFactory();
        TestUserManager defaultManager = new TestUserManager("custom", "https://custom.example.com");
        TestUserManager oidcManager = new TestUserManager("oidc", "https://oidc.example.com");
        factory.defaultManager = defaultManager;
        factory.managers.put("oidc", oidcManager);

        CredentialUserIdPassword credential = credential("alice");
        credential.setCredentialType(CredentialType.SERVICE_TOKEN);
        credential.setAuthProviderName("oidc");
        AuthCredentialService service = service(factory, Optional.of(credential));

        assertSame(defaultManager, service.resolveUserManager("alice", null));
    }

    @Test
    void resetPasswordUsesResolvedProviderAndValidatesRequest() {
        TestAuthProviderFactory factory = new TestAuthProviderFactory();
        TestUserManager defaultManager = new TestUserManager("custom", "https://custom.example.com");
        TestUserManager oidcManager = new TestUserManager("oidc", "https://oidc.example.com");
        factory.defaultManager = defaultManager;
        factory.managers.put("oidc", oidcManager);

        AuthCredentialService service = service(factory, Optional.empty());
        ResetPasswordRequest request = resetPasswordRequest("alice", "newPassword123");
        request.setAuthProvider("oidc");

        service.resetPassword(request, "");

        assertEquals("alice", oidcManager.resetUserId);
        assertEquals("newPassword123", oidcManager.resetPassword);
        assertEquals(false, oidcManager.resetForceChangePassword);
        ResetPasswordRequest invalid = resetPasswordRequest("alice", "newPassword123");
        invalid.setConfirmPassword("differentPassword123");
        assertThrows(IllegalArgumentException.class,
                () -> service.resetPassword(invalid, ""));
    }

    private static AuthCredentialService service(
            TestAuthProviderFactory factory,
            Optional<CredentialUserIdPassword> credential) {
        AuthCredentialService service = new AuthCredentialService();
        service.authProviderFactory = factory;
        service.credentialRepo = new TestCredentialRepo(credential);
        return service;
    }

    private static CredentialUserIdPassword credential(String userId) {
        return CredentialUserIdPassword.builder()
                .userId(userId)
                .subject(userId + "-subject")
                .lastUpdate(new Date())
                .domainContext(DomainContext.builder()
                        .tenantId("test-tenant")
                        .defaultRealm("test-realm")
                        .orgRefName("test-org")
                        .accountId("test-account")
                        .build())
                .build();
    }

    private static ResetPasswordRequest resetPasswordRequest(String userId, String password) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setUserId(userId);
        request.setNewPassword(password);
        request.setConfirmPassword(password);
        return request;
    }

    static class TestCredentialRepo extends CredentialRepo {
        private final Optional<CredentialUserIdPassword> credential;

        TestCredentialRepo(Optional<CredentialUserIdPassword> credential) {
            this.credential = credential;
        }

        @Override
        public Optional<CredentialUserIdPassword> findByUserId(String userId) {
            return credential.filter(c -> c.getUserId().equals(userId));
        }
    }

    static class TestAuthProviderFactory extends AuthProviderFactory {
        TestUserManager defaultManager;
        Map<String, TestUserManager> managers = new HashMap<>();
        Map<String, TestUserManager> issuers = new HashMap<>();

        @Override
        public UserManagement getUserManager() {
            return defaultManager;
        }

        @Override
        public UserManagement getUserManager(String providerName) {
            return managers.get(providerName);
        }

        @Override
        public AuthProvider getProviderForIssuer(String issuer) {
            return issuers.getOrDefault(issuer, defaultManager);
        }
    }

    static class TestUserManager implements AuthProvider, UserManagement {
        private final String name;
        private final String issuer;
        String resetUserId;
        String resetPassword;
        Boolean resetForceChangePassword;

        TestUserManager(String name, String issuer) {
            this.name = name;
            this.issuer = issuer;
        }

        @Override
        public SecurityIdentity validateAccessToken(String token) {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getIssuer() {
            return issuer;
        }

        @Override
        public LoginResponse login(String userId, String password) {
            return null;
        }

        @Override
        public LoginResponse refreshTokens(String refreshToken) {
            return null;
        }

        @Override
        public boolean removeUserWithSubject(String subject) throws ReferentialIntegrityViolationException {
            return false;
        }

        @Override
        public boolean removeUserWithUserId(String userId) throws ReferentialIntegrityViolationException {
            return false;
        }

        @Override
        public void assignRolesForUserId(String userId, Set<String> roles) throws SecurityException {}

        @Override
        public void assignRolesForSubject(String subject, Set<String> roles) throws SecurityException {}

        @Override
        public Set<String> getUserRolesForSubject(String subject) throws SecurityException {
            return Set.of();
        }

        @Override
        public Set<String> getUserRolesForUserId(String userId) throws SecurityException {
            return Set.of();
        }

        @Override
        public boolean userIdExists(String userId) throws SecurityException {
            return false;
        }

        @Override
        public boolean subjectExists(String subject) throws SecurityException {
            return false;
        }

        @Override
        public String createUser(String userId, String password, Set<String> roles, DomainContext domainContext) throws SecurityException {
            return null;
        }

        @Override
        public String createUser(String userId, String password, Boolean forceChangePassword, Set<String> roles, DomainContext domainContext) throws SecurityException {
            return null;
        }

        @Override
        public String createUser(String userId, String password, Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException {
            return null;
        }

        @Override
        public String createUser(String userId, String password, Boolean forceChangePassword, Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException {
            return null;
        }

        @Override
        public Optional<String> getSubjectForUserId(String userId) throws SecurityException {
            return Optional.empty();
        }

        @Override
        public Optional<String> getUserIdForSubject(String subject) throws SecurityException {
            return Optional.empty();
        }

        @Override
        public void changePassword(String userId, String oldPassword, String newPassword, Boolean forceChangePassword) {}

        @Override
        public void resetPassword(String userId, String newPassword, Boolean forceChangePassword) {
            resetUserId = userId;
            resetPassword = newPassword;
            resetForceChangePassword = forceChangePassword;
        }

        @Override
        public void changeEmail(String userId, String newEmail) throws SecurityException {}

        @Override
        public void resendTemporaryPassword(String userId) throws SecurityException {}

        @Override
        public void removeRolesForSubject(String subject, Set<String> roles) throws SecurityException {}

        @Override
        public void removeRolesForUserId(String userId, Set<String> roles) throws SecurityException {}

        @Override
        public void enableImpersonationWithUserId(String userId, String impersonationScript, String realmFilter, String realmToEnableIn) {}

        @Override
        public void enableImpersonationWithSubject(String subject, String impersonationScript, String realmFilter, String realmToEnableIn) {}

        @Override
        public void disableImpersonationWithSubject(String subject) {}

        @Override
        public void disableImpersonationWithUserId(String userId) {}

        @Override
        public void enableRealmOverrideWithUserId(String userId, String regexForRealm) {}

        @Override
        public void enableRealmOverrideWithSubject(String subject, String regexForRealm) {}

        @Override
        public void disableRealmOverrideWithUserId(String userId) {}

        @Override
        public void disableRealmOverrideWithSubject(String subject) {}
    }
}
