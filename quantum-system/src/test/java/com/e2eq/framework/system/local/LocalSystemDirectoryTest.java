package com.e2eq.framework.system.local;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalSystemDirectoryTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void registerRealmSavesWhileIgnoringSecurityRules() {
        RealmRepo realmRepo = mock(RealmRepo.class);
        CredentialRepo credentialRepo = mock(CredentialRepo.class);
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn("quantum-system");
        AtomicBoolean ignoredDuringSave = new AtomicBoolean();
        Realm incoming = new Realm();
        incoming.setRefName("helixor-code-D1");
        when(realmRepo.save(eq("quantum-system"), eq(incoming))).thenAnswer(invocation -> {
            ignoredDuringSave.set(SecurityContext.isIgnoringRules());
            return incoming;
        });

        LocalSystemDirectory directory = new LocalSystemDirectory(realmRepo, credentialRepo, env);
        Realm saved = directory.registerRealm(incoming);

        assertSame(incoming, saved);
        assertTrue(ignoredDuringSave.get());
        assertFalse(SecurityContext.isIgnoringRules());
    }
}
