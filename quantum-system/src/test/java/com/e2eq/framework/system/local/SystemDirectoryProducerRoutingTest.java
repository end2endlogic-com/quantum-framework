package com.e2eq.framework.system.local;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.system.remote.RemoteSystemDirectory;
import com.e2eq.framework.system.remote.SplitSystemDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Which directory each combination of deployment mode and directory mode gets. */
class SystemDirectoryProducerRoutingTest {

    private static SystemDirectoryProducer producer(QuantumModeConfig config, Optional<String> olderOverride) {
        SystemDirectoryProducer producer = new SystemDirectoryProducer();
        producer.quantumModeConfig = config;
        producer.directoryModeOverride = olderOverride;
        return producer;
    }

    @Test
    void unsetDirectoryModeKeepsTheOlderRouting() {
        assertInstanceOf(LocalSystemDirectory.class,
            producer(QuantumModeConfig.of("embedded", Optional.empty()), Optional.empty()).systemDirectory());
        assertInstanceOf(RemoteSystemDirectory.class,
            producer(QuantumModeConfig.of("remote", Optional.of("https://control.test")), Optional.empty())
                .systemDirectory());
        // The older catalog-only override still works when the new key is unset.
        assertInstanceOf(LocalSystemDirectory.class,
            producer(QuantumModeConfig.of("remote", Optional.of("https://control.test")), Optional.of("local"))
                .systemDirectory());
    }

    @Test
    void embeddedWithRemoteDirectorySplitsCatalogFromSystemRealm() {
        QuantumModeConfig config = QuantumModeConfig.of("embedded", Optional.empty(),
            Optional.of("remote"), Optional.of("https://auth.test"));

        assertInstanceOf(SplitSystemDirectory.class, producer(config, Optional.empty()).systemDirectory());
    }

    @Test
    void remoteWithRemoteDirectoryIsFullyRemote() {
        QuantumModeConfig config = QuantumModeConfig.of("remote", Optional.empty(),
            Optional.of("remote"), Optional.of("https://auth.test"));

        assertInstanceOf(RemoteSystemDirectory.class, producer(config, Optional.empty()).systemDirectory());
    }

    @Test
    void localDirectoryModeIsLocalAndWinsOverTheOlderOverride() {
        QuantumModeConfig config = QuantumModeConfig.of("remote", Optional.of("https://control.test"),
            Optional.of("local"), Optional.empty());

        assertInstanceOf(LocalSystemDirectory.class, producer(config, Optional.of("remote")).systemDirectory());
    }

    @Test
    void splitDirectorySendsCatalogRemoteAndKeepsSystemRealmLocal() {
        Recording local = new Recording("local");
        Recording catalog = new Recording("catalog");
        SplitSystemDirectory split = new SplitSystemDirectory(local, catalog);
        Realm realm = new Realm();
        realm.setRefName("acme");

        assertEquals("local-system", split.systemRealmId());
        split.findRealmByRefName("acme");
        split.findRealmByEmailDomain("acme.test");
        split.registerRealm(realm);
        split.findCredentialBySubject("subject");
        split.findCredentialByUserId("user");

        assertEquals(List.of("systemRealmId", "findCredentialBySubject", "findCredentialByUserId"), local.calls);
        assertEquals(List.of("findRealmByRefName", "findRealmByEmailDomain", "registerRealm"), catalog.calls);
    }

    private static final class Recording implements SystemDirectory {
        final String name;
        final List<String> calls = new ArrayList<>();

        Recording(String name) {
            this.name = name;
        }

        @Override public String systemRealmId() { calls.add("systemRealmId"); return name + "-system"; }
        @Override public Optional<Realm> findRealmByEmailDomain(String d) { calls.add("findRealmByEmailDomain"); return Optional.empty(); }
        @Override public Optional<Realm> findRealmByRefName(String r) { calls.add("findRealmByRefName"); return Optional.empty(); }
        @Override public Realm registerRealm(Realm realm) { calls.add("registerRealm"); return realm; }
        @Override public Optional<CredentialUserIdPassword> findCredentialBySubject(String s) { calls.add("findCredentialBySubject"); return Optional.empty(); }
        @Override public Optional<CredentialUserIdPassword> findCredentialByUserId(String u) { calls.add("findCredentialByUserId"); return Optional.empty(); }
    }
}
