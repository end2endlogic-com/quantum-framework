package com.e2eq.framework.system.local;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmSetupStatus;
import com.e2eq.framework.model.security.RealmTenancyMode;
import org.bson.types.ObjectId;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.e2eq.framework.model.persistent.base.EntityReference;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(realmRepo.save(eq("quantum-system"), any(Realm.class))).thenAnswer(invocation -> invocation.getArgument(1));

        Realm saved = new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), env).registerRealm(incoming);

        assertEquals("helixor-code", saved.getApplicationRef().getEntityRefName());
    }

    @Test
    void registerRealmWithoutAnIdUpdatesTheRecordedRealmAndKeepsWhatTheContractDoesNotCarry() {
        RealmRepo realmRepo = mock(RealmRepo.class);
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn("quantum-system");
        Realm recorded = new Realm();
        recorded.setRefName("helixor-code-D1");
        recorded.setId(new ObjectId());
        recorded.setVersion(4L);
        recorded.setDisplayName("Old name");
        recorded.setDeploymentType(RealmDeploymentType.SHARED);
        recorded.setTenancyMode(RealmTenancyMode.MULTI_TENANT);
        recorded.setDefaultPerspective("operator");
        recorded.setApplySeedsOnStartup(true);
        recorded.setSetupStatus(RealmSetupStatus.READY);
        when(realmRepo.findByRefName("helixor-code-D1", true, "quantum-system")).thenReturn(Optional.of(recorded));
        when(realmRepo.save(eq("quantum-system"), any(Realm.class))).thenAnswer(invocation -> invocation.getArgument(1));
        Realm incoming = new Realm();          // as ControlPlaneRealmMapper.fromEntry builds it
        incoming.setRefName("helixor-code-D1");
        incoming.setDisplayName("Helixor Code D1");
        incoming.setApplicationRef(application("helixor-code"));

        Realm saved = new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), env).registerRealm(incoming);

        assertSame(recorded, saved);
        assertEquals(recorded.getId(), saved.getId());
        assertEquals(4L, saved.getVersion());
        assertEquals("Helixor Code D1", saved.getDisplayName());
        assertEquals("helixor-code", saved.getApplicationRef().getEntityRefName());
        assertEquals(RealmDeploymentType.SHARED, saved.getDeploymentType());
        assertEquals(RealmTenancyMode.MULTI_TENANT, saved.getTenancyMode());
        assertEquals("operator", saved.getDefaultPerspective());
        assertTrue(saved.isApplySeedsOnStartup());
        assertEquals(RealmSetupStatus.READY, saved.getSetupStatus());
    }

    @Test
    void registerRealmRefusesToMoveARealmToAnotherApplication() {
        RealmRepo realmRepo = mock(RealmRepo.class);
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn("quantum-system");
        Realm recorded = new Realm();
        recorded.setRefName("helixor-code-D1");
        recorded.setId(new ObjectId());
        recorded.setApplicationRef(application("helixor-code"));
        when(realmRepo.findByRefName("helixor-code-D1", true, "quantum-system")).thenReturn(Optional.of(recorded));
        Realm incoming = new Realm();
        incoming.setRefName("helixor-code-D1");
        incoming.setApplicationRef(application("helixor-di"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), env).registerRealm(incoming));
        assertTrue(error.getMessage().startsWith("REALM_OWNER_CONFLICT"));
        verify(realmRepo, never()).save(anyString(), any(Realm.class));
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

        assertEquals("helixor-code", incoming.getApplicationRef().getEntityRefName());
    }
}
