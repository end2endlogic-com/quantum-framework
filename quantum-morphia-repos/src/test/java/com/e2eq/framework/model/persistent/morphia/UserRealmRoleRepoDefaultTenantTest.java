package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.UserRealmRole;
import dev.morphia.Datastore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saving a grant of a second tenant in a shared realm without a default tenant is refused. */
class UserRealmRoleRepoDefaultTenantTest {

    @Test
    void aSecondTenantWithoutADefaultIsRefused() {
        Repo repo = new Repo(RealmTenancyMode.MULTI_TENANT, grant("tenant-a"), credential("home"));
        UserRealmRoleRepo.TenantDefaultRequiredException refused = assertThrows(
                UserRealmRoleRepo.TenantDefaultRequiredException.class,
                () -> repo.requireDefaultTenant(null, grant("tenant-a", "tenant-b")));
        assertTrue(refused.getMessage().startsWith("TENANT_DEFAULT_REQUIRED"));
    }

    @Test
    void aSecondTenantIsAcceptedWhenTheCredentialTenantIsAmongThem() {
        Repo repo = new Repo(RealmTenancyMode.MULTI_TENANT, grant("tenant-a"), credential("tenant-a"));
        assertDoesNotThrow(() -> repo.requireDefaultTenant(null, grant("tenant-a", "tenant-b")));
    }

    @Test
    void reSavingAnUnchangedOrNarrowerGrantIsAccepted() {
        Repo repo = new Repo(RealmTenancyMode.MULTI_TENANT, grant("tenant-a", "tenant-b"), credential("home"));
        assertDoesNotThrow(() -> repo.requireDefaultTenant(null, grant("tenant-a", "tenant-b")));
        assertDoesNotThrow(() -> repo.requireDefaultTenant(null, grant("tenant-a")));
    }

    @Test
    void dedicatedRealmsAndIdentitiesWithoutACredentialAreNotChecked() {
        assertDoesNotThrow(() -> new Repo(RealmTenancyMode.SINGLE_TENANT, null, credential("home"))
                .requireDefaultTenant(null, grant("tenant-a", "tenant-b")));
        assertDoesNotThrow(() -> new Repo(RealmTenancyMode.MULTI_TENANT, null, null)
                .requireDefaultTenant(null, grant("tenant-a", "tenant-b")));
    }

    @Test
    void aNewTenantPatternWithoutADefaultIsRefused() {
        UserRealmRole pattern = grant("tenant-a");
        pattern.setAuthorizedTenantRegEx("tenant-.*");
        Repo repo = new Repo(RealmTenancyMode.MULTI_TENANT, grant("tenant-a"), credential("home"));
        assertThrows(UserRealmRoleRepo.TenantDefaultRequiredException.class,
                () -> repo.requireDefaultTenant(null, pattern));
    }

    private static UserRealmRole grant(String... tenants) {
        return UserRealmRole.builder().userId("alice").realmRefName("pooled")
                .authorizedTenantIds(List.of(tenants)).build();
    }

    private static CredentialUserIdPassword credential(String tenant) {
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setUserId("alice");
        credential.setDomainContext(DomainContext.builder()
                .tenantId(tenant).defaultRealm("home-realm").orgRefName("o").accountId("a").build());
        return credential;
    }

    private static final class Repo extends UserRealmRoleRepo {
        private final RealmTenancyMode mode;
        private final UserRealmRole stored;
        private final CredentialUserIdPassword credential;

        Repo(RealmTenancyMode mode, UserRealmRole stored, CredentialUserIdPassword credential) {
            this.mode = mode;
            this.stored = stored;
            this.credential = credential;
        }

        @Override
        Realm findRealm(Datastore datastore, String realmRefName) {
            return Realm.builder().refName(realmRefName).emailDomain("x.test").databaseName(realmRefName)
                    .tenancyMode(mode)
                    .domainContext(DomainContext.builder().tenantId("pooled").defaultRealm(realmRefName)
                            .orgRefName("o").accountId("a").build())
                    .build();
        }

        @Override
        UserRealmRole findStored(Datastore datastore, UserRealmRole updated) {
            return stored;
        }

        @Override
        CredentialUserIdPassword findCredential(Datastore datastore, String userId) {
            return credential;
        }
    }
}
