package com.e2eq.framework.system;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.system.remote.RemoteSystemDirectory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Plain unit test: RemoteSystemDirectory speaks the control-plane contract
 * (helixor-q/contracts/control-plane.openapi.yaml) — found/not-found mapping,
 * bearer propagation, fail-loud on unreachable control plane.
 */
public class TestRemoteSystemDirectoryClient {

    static HttpServer server;
    static volatile String lastAuthHeader;
    static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/control/realms/by-domain/acme.com", exchange -> {
            lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = ("{\"refName\":\"acme-com\",\"displayName\":\"Acme\"," +
                "\"databaseName\":\"acme-com\",\"emailDomain\":\"acme.com\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.createContext("/control/realms/by-domain/nowhere.example", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    public void mapsFoundRealmAndPropagatesBearer() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(baseUrl, Optional.of("svc-token"));
        Optional<Realm> realm = directory.findRealmByEmailDomain("acme.com");
        Assertions.assertTrue(realm.isPresent());
        Assertions.assertEquals("acme-com", realm.get().getRefName());
        Assertions.assertEquals("acme-com", realm.get().getDatabaseName());
        Assertions.assertEquals("Bearer svc-token", lastAuthHeader);
    }

    @Test
    public void notFoundMapsToEmpty() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory(baseUrl, Optional.empty());
        Assertions.assertTrue(directory.findRealmByEmailDomain("nowhere.example").isEmpty());
    }

    @Test
    public void unreachableControlPlaneFailsLoud() {
        RemoteSystemDirectory directory = new RemoteSystemDirectory("http://127.0.0.1:1", Optional.empty());
        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
            () -> directory.findRealmByRefName("acme-com"));
        Assertions.assertTrue(failure.getMessage().contains("no local fallback"));
    }
}
