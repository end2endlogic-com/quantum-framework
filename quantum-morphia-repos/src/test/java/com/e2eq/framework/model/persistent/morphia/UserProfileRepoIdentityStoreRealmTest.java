package com.e2eq.framework.model.persistent.morphia;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserProfileRepoIdentityStoreRealmTest {

    @Test
    void usesConfiguredIdentityStoreInsteadOfTenantRealm() {
        UserProfileRepo repo = new UserProfileRepo();
        repo.identityStoreRealm = Optional.of("quantum-auth");

        assertEquals("quantum-auth", repo.getSecurityContextRealmId());
        assertEquals("quantum-auth", repo.resolveIdentityStoreRealm("local-test"));
    }

    @Test
    void keepsRequestedRealmWhenIdentityStoreIsUnset() {
        UserProfileRepo repo = new UserProfileRepo();
        repo.identityStoreRealm = Optional.empty();

        assertEquals("local-test", repo.resolveIdentityStoreRealm("local-test"));
    }

    @Test
    void ignoresBlankIdentityStoreConfiguration() {
        UserProfileRepo repo = new UserProfileRepo();
        repo.identityStoreRealm = Optional.of("   ");

        assertEquals("local-test", repo.resolveIdentityStoreRealm("local-test"));
    }
}
