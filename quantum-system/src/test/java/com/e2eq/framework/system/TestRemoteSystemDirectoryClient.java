package com.e2eq.framework.system;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.system.remote.RemoteSystemDirectory;
import com.generated.helixorq.control.plane.api.DefaultEndpoint;
import com.generated.helixorq.control.plane.model.RealmCatalogEntry;
import com.generated.helixorq.control.plane.model.RealmMembershipEntry;
import com.generated.helixorq.control.plane.model.UserRealmRoleEntry;
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
                e.setEmailDomain("acme.com");
                return e;
            }
        });
        Optional<Realm> realm = directory.findRealmByRefName("acme-com");
        Assertions.assertTrue(realm.isPresent());
        Assertions.assertEquals("acme-com", realm.get().getRefName());
        Assertions.assertEquals("acme-com", realm.get().getDatabaseName());
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
