package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.oauth.model.OAuthClient;
import com.e2eq.framework.oauth.repo.OAuthClientRepo;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Exercises form decoding, endpoint routing and the serialized OAuth error contract. */
@QuarkusTest
class OAuthHttpValidationTest {
    @InjectMock OAuthClientRepo clients;
    @TestHTTPResource URI base;

    @Test
    void publicMachineGrantIsRejectedOverHttp() throws Exception {
        publicClient();
        var response = post("oauth/token", "client_id=client&grant_type=client_credentials");
        assertEquals(400, response.statusCode());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        assertEquals("unauthorized_client", json.get("error").asText());
        assertFalse(json.has("access_token"));
    }

    @Test
    void publicAuthorizationFormCannotSkipPkce() throws Exception {
        publicClient();
        var response = post("oauth/authorize", "client_id=client&redirect_uri=https%3A%2F%2Fclient.invalid%2Fcallback"
                + "&username=user&password=unused&state=roundtrip");
        assertEquals(303, response.statusCode());
        var redirect = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertEquals("client.invalid", redirect.getHost());
        assertTrue(redirect.getQuery().contains("error=invalid_request"));
        assertTrue(redirect.getQuery().contains("state=roundtrip"));
    }

    private void publicClient() {
        var client = new OAuthClient();
        client.setActive(true);
        client.setPublicClient(true);
        client.setAllowedGrantTypes(List.of("authorization_code", "client_credentials"));
        client.setRedirectUris(List.of("https://client.invalid/callback"));
        when(clients.findByClientId(anyString(), eq("client"))).thenReturn(Optional.of(client));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
