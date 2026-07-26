package com.e2eq.framework.system;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.system.remote.RemoteSystemDirectory;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * RemoteSystemDirectory as a pure mapper over the SDK-generated control-plane
 * client ({@link DefaultEndpoint}). No HTTP: a stub endpoint drives the
 * found / not-found / unreachable paths, and the identity surface fails loud
 * by design (realm-membership ADR / B4).
 */
public class TestRemoteSystemDirectoryClient {

    /** Base stub: realm lookups not-found, the rest benign (override per test). */
    static class StubEndpoint implements DefaultEndpoint {
        @Override public RealmCatalogEntry findRealmByEmailDomain(String emailDomain) { throw new NotFoundException(); }
        @Override public RealmCatalogEntry findRealmByRefName(String refName) { throw new NotFoundException(); }
        @Override public RealmCatalogEntry registerRealm(RealmCatalogEntry body) { return body; }
        @Override public List<RealmMembershipEntry> membersOfRealm(String refName) { return List.of(); }
        @Override public List<UserRealmRoleEntry> realmsForUser(String userId) { return List.of(); }
    }

    @Test
    public void mapsFoundRealm() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(new StubEndpoint() {
            @Override public RealmCatalogEntry findRealmByRefName(String refName) {
                RealmCatalogEntry e = new RealmCatalogEntry();
                e.setRefName("acme-com");
                e.setDisplayName("Acme");
                e.setDatabaseName("acme-com");
                e.setDeploymentType("SHARED");
                e.setEmailDomain("acme.com");
                e.setTenantId("acme.com");
                e.setOrgRefName("acme");
                e.setAccountNumber("0000000001");
                return e;
            }
        });
        Optional<Realm> realm = directory.findRealmByRefName("acme-com");
        Assertions.assertTrue(realm.isPresent());
        Assertions.assertEquals("acme-com", realm.get().getRefName());
        Assertions.assertEquals("acme-com", realm.get().getDatabaseName());
        Assertions.assertEquals(
            RealmDeploymentType.SHARED, realm.get().getDeploymentType());
        Assertions.assertNotNull(realm.get().getDomainContext());
        Assertions.assertEquals("acme.com", realm.get().getDomainContext().getTenantId());
        Assertions.assertEquals("acme", realm.get().getDomainContext().getOrgRefName());
        Assertions.assertEquals("0000000001", realm.get().getDomainContext().getAccountId());
        Assertions.assertEquals("acme-com", realm.get().getDomainContext().getDefaultRealm());
    }

    @Test
    public void absentRemoteDeploymentTypeDefaultsToDedicated() {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName("legacy");
        entry.setDatabaseName("legacy");
        entry.setEmailDomain("legacy.example");

        Realm realm =
            com.e2eq.framework.system.remote.ControlPlaneRealmMapper.fromEntry(entry);

        Assertions.assertEquals(
            RealmDeploymentType.DEDICATED, realm.getDeploymentType());
    }

    @Test
    public void notFoundMapsToEmpty() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(new StubEndpoint());
        Assertions.assertTrue(directory.findRealmByEmailDomain("nowhere.example").isEmpty());
    }

    @Test
    public void unreachableControlPlaneFailsLoud() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(new StubEndpoint() {
            @Override public RealmCatalogEntry findRealmByRefName(String refName) {
                throw new ProcessingException("connection refused");
            }
        });
        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
            () -> directory.findRealmByRefName("acme-com"));
        Assertions.assertTrue(failure.getMessage().contains("no local fallback"));
    }

    @Test
    public void identitySurfaceFailsLoudByDesign() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(new StubEndpoint());
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
            directory::systemRealmId).getMessage().contains("remote mode"));
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
            () -> directory.findCredentialByUserId("anyone@example.com")).getMessage()
            .contains("control-plane-internal"));
    }
}
