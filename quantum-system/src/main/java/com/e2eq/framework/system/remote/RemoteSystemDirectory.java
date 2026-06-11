package com.e2eq.framework.system.remote;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Phase C of the control-plane split: SystemDirectory backed by the
 * control-plane HTTP API (helixor-q/contracts/control-plane.openapi.yaml;
 * a generated client exists at helixor-q/generated/control-plane-sdk — this
 * implementation speaks the same contract with java.net.http to avoid a
 * cross-repo build dependency until the SDK jar is published).
 *
 * Deliberate fail-loud surface in remote mode:
 * - {@link #systemRealmId()} — a tier-2 deployment has NO local system realm;
 *   any caller needing the realm string is on a path the control plane owns.
 * - credential lookups — identity is validated via the platform JWKS;
 *   credential material never crosses the tenant->system seam BY DESIGN
 *   (realm-membership ADR / B4). A code path requesting raw credentials in a
 *   tier-2 app is an architecture error to surface, not to serve.
 */
public class RemoteSystemDirectory implements SystemDirectory {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final Optional<String> bearerToken;
    private final HttpClient http;

    public RemoteSystemDirectory(String baseUrl, Optional<String> bearerToken) {
        this(baseUrl, bearerToken, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    RemoteSystemDirectory(String baseUrl, Optional<String> bearerToken, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.bearerToken = bearerToken;
        this.http = http;
    }

    @Override
    public String systemRealmId() {
        throw new IllegalStateException(
            "systemRealmId() is unavailable in remote mode: a tier-2 deployment has no local "
            + "system realm — the control plane owns it. The calling path must be ported to the "
            + "typed SystemDirectory/RealmCatalog operations (control-plane split Phase C).");
    }

    @Override
    public Optional<Realm> findRealmByEmailDomain(String emailDomain) {
        return getRealm("/control/realms/by-domain/" + encode(emailDomain));
    }

    @Override
    public Optional<Realm> findRealmByRefName(String refName) {
        return getRealm("/control/realms/" + encode(refName));
    }

    @Override
    public Realm registerRealm(Realm realm) {
        try {
            HttpRequest request = builder("/control/realms/" + encode(realm.getRefName()))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(toEntry(realm))))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Control plane rejected realm registration for "
                    + realm.getRefName() + ": HTTP " + response.statusCode());
            }
            return fromEntry(MAPPER.readTree(response.body()));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Control plane unreachable registering realm "
                + realm.getRefName() + " at " + baseUrl + " — failing loud, no local fallback.", e);
        }
    }

    @Override
    public Optional<CredentialUserIdPassword> findCredentialBySubject(String subject) {
        throw credentialLookupsAreControlPlaneInternal();
    }

    @Override
    public Optional<CredentialUserIdPassword> findCredentialByUserId(String userId) {
        throw credentialLookupsAreControlPlaneInternal();
    }

    private static IllegalStateException credentialLookupsAreControlPlaneInternal() {
        return new IllegalStateException(
            "Credential lookups are control-plane-internal: in remote mode identity is validated "
            + "via the platform JWKS and credential material never crosses the tenant->system seam "
            + "(realm-membership ADR / B4). Port the calling path to JWT-claims-based identity.");
    }

    private Optional<Realm> getRealm(String path) {
        try {
            HttpResponse<String> response = http.send(
                builder(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Control plane returned HTTP "
                    + response.statusCode() + " for " + path);
            }
            return Optional.of(fromEntry(MAPPER.readTree(response.body())));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Control plane unreachable for " + path + " at "
                + baseUrl + " — failing loud, no local fallback.", e);
        }
    }

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10));
        bearerToken.filter(t -> !t.isBlank())
            .ifPresentOrElse(
                t -> b.header("Authorization", "Bearer " + t),
                () -> Log.debugf("RemoteSystemDirectory call without bearer token: %s", path));
        return b;
    }

    /** Contract mapping: RealmCatalogEntry <-> Realm (catalog fields only). */
    static Realm fromEntry(JsonNode entry) {
        Realm realm = new Realm();
        realm.setRefName(text(entry, "refName"));
        realm.setDisplayName(text(entry, "displayName"));
        realm.setDatabaseName(text(entry, "databaseName"));
        realm.setEmailDomain(text(entry, "emailDomain"));
        realm.setConnectionString(text(entry, "connectionString"));
        realm.setDefaultAdminUserId(text(entry, "defaultAdminUserId"));
        return realm;
    }

    static com.fasterxml.jackson.databind.node.ObjectNode toEntry(Realm realm) {
        var node = MAPPER.createObjectNode();
        node.put("refName", realm.getRefName());
        node.put("displayName", realm.getDisplayName());
        node.put("databaseName", realm.getDatabaseName());
        node.put("emailDomain", realm.getEmailDomain());
        node.put("connectionString", realm.getConnectionString());
        node.put("defaultAdminUserId", realm.getDefaultAdminUserId());
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
