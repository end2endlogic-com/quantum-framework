package com.e2eq.framework.system.local;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.e2eq.framework.model.persistent.base.EntityReference;
import java.util.Optional;
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

    private static EntityReference application(String refName) {
        return EntityReference.builder().entityType("Application")
            .entityRefName(refName).entityDisplayName(refName).build();
    }

    @Test
    void registerRealmWithoutAnOwnerKeepsTheRecordedOwner() {
        RealmRepo realmRepo = mock(RealmRepo.class);
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn("quantum-system");
        Realm recorded = new Realm();
        recorded.setRefName("helixor-code-D1");
        recorded.setApplicationRef(application("helixor-code"));
        when(realmRepo.findByRefName("helixor-code-D1", true, "quantum-system")).thenReturn(Optional.of(recorded));
        Realm incoming = new Realm();
        incoming.setRefName("helixor-code-D1");
        when(realmRepo.save(eq("quantum-system"), eq(incoming))).thenReturn(incoming);

        new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), env).registerRealm(incoming);

        org.junit.jupiter.api.Assertions.assertEquals("helixor-code", incoming.getApplicationRef().getEntityRefName());
    }

    @Test
    void registerRealmNamingAnOwnerWritesThatOwner() {
        RealmRepo realmRepo = mock(RealmRepo.class);
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn("quantum-system");
        Realm incoming = new Realm();
        incoming.setRefName("helixor-code-D1");
        incoming.setApplicationRef(application("helixor-code"));
        when(realmRepo.save(eq("quantum-system"), eq(incoming))).thenReturn(incoming);

        new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), env).registerRealm(incoming);

        org.junit.jupiter.api.Assertions.assertEquals("helixor-code", incoming.getApplicationRef().getEntityRefName());
        org.mockito.Mockito.verify(realmRepo, org.mockito.Mockito.never())
            .findByRefName(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
