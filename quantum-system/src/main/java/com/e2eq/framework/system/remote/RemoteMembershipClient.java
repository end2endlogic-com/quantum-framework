package com.e2eq.framework.system.remote;

import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase C (2/2): membership/role resolution against the control-plane API
 * (/control/realms/{ref}/members, /control/users/{id}/realms — see
 * helixor-q/contracts/control-plane.openapi.yaml). Same posture as
 * RemoteSystemDirectory: optional service bearer, strict fail-loud, no
 * local fallback.
 */
public class RemoteMembershipClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String baseUrl;
    private final Optional<String> bearerToken;
    private final HttpClient http;

    public RemoteMembershipClient(String baseUrl, Optional<String> bearerToken) {
        this(baseUrl, bearerToken, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    RemoteMembershipClient(String baseUrl, Optional<String> bearerToken, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.bearerToken = bearerToken;
        this.http = http;
    }

    public List<RealmTenantMembership> membersOfRealm(String realmRefName) {
        JsonNode array = getArray("/control/realms/" + encode(realmRefName) + "/members");
        List<RealmTenantMembership> members = new ArrayList<>();
        for (JsonNode entry : array) {
            RealmTenantMembership membership = new RealmTenantMembership();
            membership.setRealmRefName(text(entry, "realmRefName"));
            membership.setOrganizationRefName(text(entry, "organizationRefName"));
            membership.setAccountId(text(entry, "accountId"));
            membership.setTenantId(text(entry, "tenantId"));
            membership.setMembershipRole(Optional.ofNullable(text(entry, "membershipRole"))
                .orElse(RealmTenantMembership.MEMBERSHIP_ROLE_OWNER));
            membership.setParticipationStatus(text(entry, "participationStatus"));
            members.add(membership);
        }
        return members;
    }

    public List<UserRealmRole> realmsForUser(String userId) {
        JsonNode array = getArray("/control/users/" + encode(userId) + "/realms");
        List<UserRealmRole> assignments = new ArrayList<>();
        for (JsonNode entry : array) {
            UserRealmRole assignment = new UserRealmRole();
            assignment.setUserId(text(entry, "userId"));
            assignment.setRealmRefName(text(entry, "realmRefName"));
            assignment.setSponsoringOrgRefName(text(entry, "sponsoringOrgRefName"));
            assignment.setStatus(text(entry, "status"));
            List<String> roles = new ArrayList<>();
            JsonNode rolesNode = entry.get("roles");
            if (rolesNode != null && rolesNode.isArray()) {
                rolesNode.forEach(role -> roles.add(role.asText()));
            }
            assignment.setRoles(roles);
            assignments.add(assignment);
        }
        return assignments;
    }

    private JsonNode getArray(String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10)).GET();
            bearerToken.filter(t -> !t.isBlank())
                .ifPresent(t -> builder.header("Authorization", "Bearer " + t));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Control plane returned HTTP "
                    + response.statusCode() + " for " + path);
            }
            JsonNode node = MAPPER.readTree(response.body());
            if (!node.isArray()) {
                throw new IllegalStateException("Control plane returned non-array body for " + path);
            }
            return node;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Control plane unreachable for " + path + " at "
                + baseUrl + " — failing loud, no local fallback.", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
