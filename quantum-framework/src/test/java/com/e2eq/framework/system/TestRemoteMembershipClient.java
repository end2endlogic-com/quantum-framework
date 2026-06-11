package com.e2eq.framework.system;

import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.system.remote.RemoteMembershipClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Contract-mapping test for the remote membership client (Phase C 2/2). */
public class TestRemoteMembershipClient {

    static HttpServer server;
    static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/control/realms/fulfillment-demo-com/members", exchange -> {
            byte[] body = ("[" +
                "{\"realmRefName\":\"fulfillment-demo-com\",\"organizationRefName\":\"acme.com\",\"membershipRole\":\"owner\"}," +
                "{\"realmRefName\":\"fulfillment-demo-com\",\"organizationRefName\":\"fastfreight.com\",\"membershipRole\":\"participant\"}]")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.createContext("/control/users/", exchange -> {
            byte[] body = ("[" +
                "{\"userId\":\"pat@acme.com\",\"realmRefName\":\"fulfillment-demo-com\",\"roles\":[\"admin\"],\"status\":\"active\"}," +
                "{\"userId\":\"pat@acme.com\",\"realmRefName\":\"other-com\",\"roles\":[\"viewer\"],\"status\":\"suspended\"}]")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    public void mapsRealmMembers() {
        RemoteMembershipClient client = new RemoteMembershipClient(baseUrl, Optional.empty());
        List<RealmTenantMembership> members = client.membersOfRealm("fulfillment-demo-com");
        Assertions.assertEquals(2, members.size());
        Assertions.assertEquals("owner", members.get(0).getMembershipRole());
        Assertions.assertEquals("fastfreight.com", members.get(1).getOrganizationRefName());
    }

    @Test
    public void mapsUserRealmRolesIncludingStatus() {
        RemoteMembershipClient client = new RemoteMembershipClient(baseUrl, Optional.empty());
        var assignments = client.realmsForUser("pat@acme.com");
        Assertions.assertEquals(2, assignments.size());
        Assertions.assertEquals(List.of("admin"), assignments.get(0).getRoles());
        Assertions.assertEquals("suspended", assignments.get(1).getStatus());
    }

    @Test
    public void unreachableControlPlaneFailsLoud() {
        RemoteMembershipClient client = new RemoteMembershipClient("http://127.0.0.1:1", Optional.empty());
        Assertions.assertTrue(Assertions.assertThrows(IllegalStateException.class,
            () -> client.membersOfRealm("x")).getMessage().contains("no local fallback"));
    }
}
