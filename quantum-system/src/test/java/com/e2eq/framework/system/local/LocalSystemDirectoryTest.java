package com.e2eq.framework.system.local;

import com.e2eq.framework.api.system.RealmRegistrationConflictException;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.DomainContext;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
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
        when(realmRepo.findAllByRefNameWithIgnoreRules("helixor-code-D1", "quantum-system")).thenReturn(List.of(recorded));
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
        when(realmRepo.findAllByRefNameWithIgnoreRules("helixor-code-D1", "quantum-system")).thenReturn(List.of(recorded));
        when(realmRepo.save(eq("quantum-system"), any(Realm.class))).thenAnswer(invocation -> invocation.getArgument(1));
        Realm incoming = new Realm();          // as ControlPlaneRealmMapper.fromEntry builds it
        incoming.setRefName("helixor-code-D1");
        incoming.setDisplayName("Helixor Code D1");
        incoming.setDeploymentType(RealmDeploymentType.SHARED);
        incoming.setTenancyMode(RealmTenancyMode.MULTI_TENANT);
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
        when(realmRepo.findAllByRefNameWithIgnoreRules("helixor-code-D1", "quantum-system")).thenReturn(List.of(recorded));
        Realm incoming = new Realm();
        incoming.setRefName("helixor-code-D1");
        incoming.setApplicationRef(application("helixor-di"));

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), env).registerRealm(incoming));
        assertEquals(RealmRegistrationConflictException.Kind.REALM_OWNER_CONFLICT, error.kind());
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

    private static Realm recordedDedicatedRealm() {
        Realm recorded = new Realm();
        recorded.setId(new ObjectId());
        recorded.setRefName("acme-com");
        recorded.setDisplayName("Acme");
        recorded.setEmailDomain("acme.com");
        recorded.setDatabaseName("acme-com");
        recorded.setConnectionString("mongodb://tenant-db:27017");
        recorded.setDefaultAdminUserId("admin@acme.com");
        recorded.setDomainContext(DomainContext.builder().tenantId("acme.com").orgRefName("acme")
            .accountId("0000000042").defaultRealm("acme-com").build());
        return recorded;
    }

    /** What a remote PUT for the recorded realm carries: the catalog fields, no id. */
    private static Realm catalogWriteOf(Realm recorded) {
        Realm incoming = new Realm();
        incoming.setRefName(recorded.getRefName());
        incoming.setDisplayName(recorded.getDisplayName());
        incoming.setEmailDomain(recorded.getEmailDomain());
        incoming.setDatabaseName(recorded.getDatabaseName());
        incoming.setConnectionString(recorded.getConnectionString());
        incoming.setDefaultAdminUserId(recorded.getDefaultAdminUserId());
        incoming.setDeploymentType(recorded.getDeploymentType());
        incoming.setTenancyMode(recorded.getTenancyMode());
        incoming.setDomainContext(DomainContext.builder().tenantId("acme.com").orgRefName("acme")
            .accountId("0000000042").defaultRealm("acme-com").build());
        return incoming;
    }

    private static RealmRepo repoRecording(Realm recorded) {
        RealmRepo realmRepo = mock(RealmRepo.class);
        when(realmRepo.findAllByRefNameWithIgnoreRules(recorded.getRefName(), "quantum-system")).thenReturn(List.of(recorded));
        when(realmRepo.save(eq("quantum-system"), any(Realm.class))).thenAnswer(invocation -> invocation.getArgument(1));
        return realmRepo;
    }

    private static EnvConfigUtils systemRealm() {
        EnvConfigUtils env = mock(EnvConfigUtils.class);
        when(env.getSystemRealm()).thenReturn("quantum-system");
        return env;
    }

    @Test
    void registerRealmRefusesToChangeTheDatabaseConnectionDomainOrAdminOfARecordedRealm() {
        Realm recorded = recordedDedicatedRealm();
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = catalogWriteOf(recorded);
        incoming.setDatabaseName("attacker-db");
        incoming.setConnectionString("mongodb://elsewhere:27017");
        incoming.setDefaultAdminUserId("someone@else.com");
        incoming.setDomainContext(DomainContext.builder().tenantId("other.com").orgRefName("acme")
            .accountId("0000000042").defaultRealm("acme-com").build());

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming));

        assertEquals(RealmRegistrationConflictException.Kind.REALM_RESTRICTED_FIELD_CHANGE, error.kind());
        assertEquals(List.of("databaseName", "connectionString", "defaultAdminUserId", "domainContext"), error.fields());
        verify(realmRepo, never()).save(anyString(), any(Realm.class));
        assertEquals("mongodb://tenant-db:27017", recorded.getConnectionString());
        assertEquals("acme-com", recorded.getDatabaseName());
    }

    @Test
    void registerRealmRefusesToChangeTopologyTenancyOrEmailDomainOfARecordedRealm() {
        Realm recorded = recordedDedicatedRealm();
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = catalogWriteOf(recorded);
        incoming.setDeploymentType(RealmDeploymentType.SHARED);
        incoming.setTenancyMode(RealmTenancyMode.MULTI_TENANT);
        incoming.setEmailDomain("other.com");

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming));

        assertEquals(List.of("emailDomain", "deploymentType", "tenancyMode"), error.fields());
        verify(realmRepo, never()).save(anyString(), any(Realm.class));
    }

    @Test
    void registerRealmBackfillsAnOwnerOntoARecordedRealmWithoutOne() {
        Realm recorded = recordedDedicatedRealm();
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = catalogWriteOf(recorded);
        incoming.setDisplayName("Acme Inc");
        incoming.setApplicationRef(application("helixor-code"));

        Realm saved = new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming);

        assertSame(recorded, saved);
        assertEquals("helixor-code", saved.getApplicationRef().getEntityRefName());
        assertEquals("Acme Inc", saved.getDisplayName());
        assertEquals("mongodb://tenant-db:27017", saved.getConnectionString());
    }

    @Test
    void registerRealmCannotChangeAnExistingOwner() {
        Realm recorded = recordedDedicatedRealm();
        recorded.setApplicationRef(application("helixor-code"));
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = catalogWriteOf(recorded);
        incoming.setApplicationRef(application("helixor-di"));

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming));

        assertEquals(RealmRegistrationConflictException.Kind.REALM_OWNER_CONFLICT, error.kind());
        assertEquals("helixor-code", recorded.getApplicationRef().getEntityRefName());
        verify(realmRepo, never()).save(anyString(), any(Realm.class));
    }

    @Test
    void registerRealmForARecordedRefNameNeverInsertsASecondDocument() {
        Realm recorded = recordedDedicatedRealm();
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = catalogWriteOf(recorded);

        new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming);

        verify(realmRepo).save(eq("quantum-system"), same(recorded));
        verify(realmRepo, never()).save(anyString(), same(incoming));
    }

    @Test
    void registerRealmCarryingOnlyADisplayNameRenamesTheRecordedRealm() {
        Realm recorded = recordedDedicatedRealm();
        recorded.setDefaultAdminUserId("ops@acme.com");
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = new Realm();
        incoming.setRefName("acme-com");
        incoming.setDisplayName("Acme Renamed");

        Realm saved = new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming);

        assertSame(recorded, saved);
        assertEquals("Acme Renamed", saved.getDisplayName());
        assertEquals("ops@acme.com", saved.getDefaultAdminUserId());
    }

    @Test
    void registerRealmThatOmitsTopologyCannotTurnASharedRealmDedicated() {
        Realm recorded = recordedDedicatedRealm();
        recorded.setDeploymentType(RealmDeploymentType.SHARED);
        recorded.setTenancyMode(RealmTenancyMode.MULTI_TENANT);
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = new Realm();          // fromEntry maps absent topology to DEDICATED / SINGLE_TENANT
        incoming.setRefName("acme-com");
        incoming.setDisplayName("Acme");

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming));

        assertEquals(List.of("deploymentType", "tenancyMode"), error.fields());
        assertEquals(RealmDeploymentType.SHARED, recorded.getDeploymentType());
    }

    @Test
    void registerRealmWithAnIdIsSavedAsGivenWithoutALookup() {
        RealmRepo realmRepo = mock(RealmRepo.class);
        Realm incoming = recordedDedicatedRealm();
        when(realmRepo.save(eq("quantum-system"), same(incoming))).thenReturn(incoming);

        new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming);

        verify(realmRepo, never()).findAllByRefNameWithIgnoreRules(anyString(), anyString());
        verify(realmRepo).save(eq("quantum-system"), same(incoming));
    }

    @Test
    void registerRealmRefusesAnExplicitAdminChangeEvenWithoutAnEmailDomain() {
        Realm recorded = recordedDedicatedRealm();
        RealmRepo realmRepo = repoRecording(recorded);
        Realm incoming = new Realm();
        incoming.setRefName("acme-com");
        incoming.setDefaultAdminUserId("someone@else.com");

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming));

        assertEquals(List.of("defaultAdminUserId"), error.fields());
        verify(realmRepo, never()).save(anyString(), any(Realm.class));
    }

    @Test
    void registerRealmWithDuplicatesUsesTheOldestAndDeletesNothing() throws Exception {
        Realm oldest = recordedDedicatedRealm();
        oldest.setId(new ObjectId("6ad1440ca6e53a647f9f8509"));
        Realm duplicate = recordedDedicatedRealm();
        duplicate.setId(new ObjectId("6ad1440ca6e53a647f9f8510"));
        duplicate.setDisplayName("Duplicate");
        RealmRepo realmRepo = mock(RealmRepo.class);
        when(realmRepo.findAllByRefNameWithIgnoreRules("acme-com", "quantum-system"))
            .thenReturn(List.of(oldest, duplicate));
        when(realmRepo.save(eq("quantum-system"), any(Realm.class))).thenAnswer(invocation -> invocation.getArgument(1));
        Realm incoming = catalogWriteOf(oldest);
        incoming.setDisplayName("Acme Renamed");

        Realm saved = new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming);

        assertSame(oldest, saved);
        assertEquals("Acme Renamed", saved.getDisplayName());
        assertEquals("Duplicate", duplicate.getDisplayName());
        verify(realmRepo).save("quantum-system", oldest);
        verify(realmRepo, never()).save("quantum-system", duplicate);
        verify(realmRepo, never()).delete(anyString(), any(Realm.class));
    }

    @Test
    void registerRealmWithDuplicatesComparesAgainstTheOldest() {
        Realm oldest = recordedDedicatedRealm();
        oldest.setId(new ObjectId("6ad1440ca6e53a647f9f8509"));
        Realm duplicate = recordedDedicatedRealm();
        duplicate.setId(new ObjectId("6ad1440ca6e53a647f9f8510"));
        duplicate.setDatabaseName("duplicate-db");
        RealmRepo realmRepo = mock(RealmRepo.class);
        when(realmRepo.findAllByRefNameWithIgnoreRules("acme-com", "quantum-system"))
            .thenReturn(List.of(oldest, duplicate));
        Realm incoming = catalogWriteOf(duplicate);

        RealmRegistrationConflictException error = assertThrows(RealmRegistrationConflictException.class,
            () -> new LocalSystemDirectory(realmRepo, mock(CredentialRepo.class), systemRealm()).registerRealm(incoming));

        assertEquals(List.of("databaseName"), error.fields());
        verify(realmRepo, never()).save(anyString(), any(Realm.class));
    }
}
