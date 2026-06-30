package com.e2eq.framework.service.startup;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for the {@code quantum.baseline-identity.seed-system-only} guard in
 * {@link BaselineIdentityStartupService}.
 *
 * <p>Mirrors the dependency-recording style of
 * {@code com.e2eq.framework.bootstrap.runtime.BootstrapPackStartupRunnerTest}: the service is
 * constructed directly with hand-written recording repositories so the test runs without Quarkus
 * or Mongo. {@code SecurityCallScope} is pure ThreadLocal, so the production code paths run as-is.
 */
class BaselineIdentitySeedSystemOnlyTest {

    @Test
    void defaultSeedsSystemAndDefaultAndTestAdmins() {
        RecordingCredentialRepo credentialRepo = new RecordingCredentialRepo();
        RecordingUserProfileRepo userProfileRepo = new RecordingUserProfileRepo();
        RecordingUserGroupRepo userGroupRepo = new RecordingUserGroupRepo();

        BaselineIdentityStartupService service = newService(false, credentialRepo, userProfileRepo, userGroupRepo);
        service.onStart();

        // System user is always seeded.
        assertTrue(credentialRepo.savedUserIds().contains("system@system.com"),
                "system user must be seeded in default mode");
        // System-tenant admin + default-tenant + test-tenant admins are all seeded in default mode.
        assertTrue(credentialRepo.savedUserIds().contains("admin@system.com"),
                "system-tenant admin must be seeded in default mode");
        assertTrue(credentialRepo.savedUserIds().contains("admin@mycompanyxyz.com"),
                "default-tenant admin must be seeded in default mode");
        assertTrue(credentialRepo.savedUserIds().contains("admin@test-quantum.com"),
                "test-tenant admin must be seeded in default mode");

        // Tenant-admin groups are created for the tenant admins (default + test realms at minimum).
        assertTrue(userGroupRepo.savedRealms().contains("mycompanyxyz-com"),
                "tenant-admin group expected for default realm in default mode");
        assertTrue(userGroupRepo.savedRealms().contains("test-quantum-com"),
                "tenant-admin group expected for test realm in default mode");
    }

    @Test
    void seedSystemOnlySeedsOnlySystemIdentity() {
        RecordingCredentialRepo credentialRepo = new RecordingCredentialRepo();
        RecordingUserProfileRepo userProfileRepo = new RecordingUserProfileRepo();
        RecordingUserGroupRepo userGroupRepo = new RecordingUserGroupRepo();

        BaselineIdentityStartupService service = newService(true, credentialRepo, userProfileRepo, userGroupRepo);
        service.onStart();

        // System user IS still created under system-only.
        assertTrue(credentialRepo.savedUserIds().contains("system@system.com"),
                "system user MUST still be seeded under seed-system-only");
        // The system-tenant admin is part of the system identity and remains seeded.
        assertTrue(credentialRepo.savedUserIds().contains("admin@system.com"),
                "system-tenant admin remains seeded under seed-system-only");

        // No tenant-shaped (default/test) admins.
        assertFalse(credentialRepo.savedUserIds().contains("admin@mycompanyxyz.com"),
                "default-tenant admin must NOT be seeded under seed-system-only");
        assertFalse(credentialRepo.savedUserIds().contains("admin@test-quantum.com"),
                "test-tenant admin must NOT be seeded under seed-system-only");

        // No tenant-admin groups for default/test realms.
        assertFalse(userGroupRepo.savedRealms().contains("mycompanyxyz-com"),
                "no default-realm tenant-admin group under seed-system-only");
        assertFalse(userGroupRepo.savedRealms().contains("test-quantum-com"),
                "no test-realm tenant-admin group under seed-system-only");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static BaselineIdentityStartupService newService(boolean seedSystemOnly,
                                                             RecordingCredentialRepo credentialRepo,
                                                             RecordingUserProfileRepo userProfileRepo,
                                                             RecordingUserGroupRepo userGroupRepo) {
        BaselineIdentityStartupService service = new BaselineIdentityStartupService();
        service.authProviderFactory = new StubAuthProviderFactory();
        service.credentialRepo = credentialRepo;
        service.userProfileRepo = userProfileRepo;
        service.userGroupRepo = userGroupRepo;
        service.envConfigUtils = env();
        service.defaultSystemPassword = "test123456";
        service.defaultDemoPassword = "test123!";
        service.seedSystemOnly = seedSystemOnly;
        return service;
    }

    private static EnvConfigUtils env() {
        EnvConfigUtils utils = new EnvConfigUtils();
        utils.setSystemRealm("system-com");
        utils.setSystemTenantId("system.com");
        utils.setSystemOrgRefName("system.com");
        utils.setSystemAccountNumber("0000000000");
        utils.setSystemUserId("system@system.com");
        utils.setDefaultRealm("mycompanyxyz-com");
        utils.setDefaultTenantId("mycompanyxyz.com");
        utils.setDefaultOrgRefName("mycompanyxyz.com");
        utils.setDefaultAccountNumber("9999999999");
        utils.setTestRealm("test-quantum-com");
        utils.setTestTenantId("test-quantum.com");
        utils.setTestOrgRefName("test-quantum.com");
        utils.setTestAccountNumber("0000000001");
        return utils;
    }

    private static final class StubAuthProviderFactory extends AuthProviderFactory {
        @Override
        public AuthProvider getAuthProvider() {
            return new AuthProvider() {
                @Override
                public SecurityIdentity validateAccessToken(String token) {
                    return null;
                }

                @Override
                public String getName() {
                    return "custom";
                }

                @Override
                public LoginResponse login(String userId, String password) {
                    return null;
                }

                @Override
                public LoginResponse refreshTokens(String refreshToken) {
                    return null;
                }
            };
        }
    }

    private static final class RecordingCredentialRepo extends CredentialRepo {
        // userId -> stored credential (so the second findByUserId after create returns it)
        private final Map<String, CredentialUserIdPassword> store = new LinkedHashMap<>();
        private final List<String> saved = new ArrayList<>();

        List<String> savedUserIds() {
            return saved;
        }

        @Override
        public Optional<CredentialUserIdPassword> findByUserId(String userId, String realmId, boolean ignoreRules) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public CredentialUserIdPassword save(String realmId, CredentialUserIdPassword value) {
            store.put(value.getUserId(), value);
            saved.add(value.getUserId());
            return value;
        }
    }

    private static final class RecordingUserProfileRepo extends UserProfileRepo {
        @Override
        public Optional<UserProfile> getByUserIdWithIgnoreRules(String realm, String userId) {
            return Optional.empty();
        }

        @Override
        public UserProfile persistStartupProfile(String realm, UserProfile profile) {
            return profile;
        }
    }

    private static final class RecordingUserGroupRepo extends UserGroupRepo {
        private final List<String> savedRealms = new ArrayList<>();

        List<String> savedRealms() {
            return savedRealms;
        }

        @Override
        public Optional<UserGroup> findByRefName(String refName, String realmId) {
            return Optional.empty();
        }

        @Override
        public UserGroup save(String realmId, UserGroup value) {
            savedRealms.add(realmId);
            return value;
        }
    }
}
