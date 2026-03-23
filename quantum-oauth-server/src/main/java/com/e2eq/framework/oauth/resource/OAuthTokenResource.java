package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.provider.jwtToken.TokenUtils;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.oauth.model.AuthorizationCode;
import com.e2eq.framework.oauth.model.OAuthClient;
import com.e2eq.framework.oauth.repo.AuthorizationCodeRepo;
import com.e2eq.framework.oauth.repo.OAuthClientRepo;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * OAuth 2.0 Token endpoint.
 * Supports grant types: authorization_code, client_credentials, refresh_token.
 */
@Path("/oauth/token")
@PermitAll
public class OAuthTokenResource {

    @Inject
    OAuthClientRepo oauthClientRepo;

    @Inject
    AuthorizationCodeRepo authCodeRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    EnvConfigUtils envConfigUtils;

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @ConfigProperty(name = "com.b2bi.jwt.duration")
    long tokenDuration;

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader,
            @FormParam("grant_type") String grantType,
            @FormParam("code") String code,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("client_id") String clientIdParam,
            @FormParam("client_secret") String clientSecretParam,
            @FormParam("refresh_token") String refreshToken,
            @FormParam("scope") String scope) {

        // Resolve client credentials from either Basic auth header or form params
        String[] clientCreds = resolveClientCredentials(authHeader, clientIdParam, clientSecretParam);
        String clientId = clientCreds[0];
        String clientSecret = clientCreds[1];

        if (clientId == null || clientId.isBlank()) {
            return errorResponse("invalid_request", "client_id is required");
        }

        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<OAuthClient> clientOpt = oauthClientRepo.findByClientId(systemRealm, clientId);
        if (clientOpt.isEmpty()) {
            return errorResponse("invalid_client", "Unknown client_id");
        }

        OAuthClient client = clientOpt.get();

        // Validate client secret for confidential clients
        if (!client.isPublicClient()) {
            if (clientSecret == null || clientSecret.isBlank()) {
                return errorResponse("invalid_client", "client_secret is required");
            }
            if (!EncryptionUtils.checkPassword(clientSecret, client.getClientSecretHash())) {
                return errorResponse("invalid_client", "Invalid client_secret");
            }
        }

        if (grantType == null) {
            return errorResponse("invalid_request", "grant_type is required");
        }

        if (!client.getAllowedGrantTypes().contains(grantType)) {
            return errorResponse("unauthorized_client",
                    "This client is not authorized for grant_type: " + grantType);
        }

        return switch (grantType) {
            case "authorization_code" -> handleAuthorizationCode(client, code, redirectUri, codeVerifier);
            case "client_credentials" -> handleClientCredentials(client);
            case "refresh_token" -> handleRefreshToken(client, refreshToken);
            default -> errorResponse("unsupported_grant_type", "Unsupported grant_type: " + grantType);
        };
    }

    private Response handleAuthorizationCode(OAuthClient client, String code,
                                             String redirectUri, String codeVerifier) {
        if (code == null || code.isBlank()) {
            return errorResponse("invalid_request", "code is required");
        }

        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<AuthorizationCode> codeOpt = authCodeRepo.findValidCode(systemRealm, code);
        if (codeOpt.isEmpty()) {
            return errorResponse("invalid_grant", "Invalid, expired, or already consumed authorization code");
        }

        AuthorizationCode authCode = codeOpt.get();

        // Verify the code belongs to this client
        if (!client.getClientId().equals(authCode.getClientId())) {
            return errorResponse("invalid_grant", "Authorization code was not issued to this client");
        }

        // Verify redirect_uri matches
        if (authCode.getRedirectUri() != null && !authCode.getRedirectUri().equals(redirectUri)) {
            return errorResponse("invalid_grant", "redirect_uri mismatch");
        }

        // Verify PKCE code_verifier
        if (authCode.getCodeChallenge() != null) {
            if (codeVerifier == null || codeVerifier.isBlank()) {
                return errorResponse("invalid_request", "code_verifier is required for PKCE");
            }
            if (!verifyPkce(codeVerifier, authCode.getCodeChallenge())) {
                return errorResponse("invalid_grant", "PKCE verification failed");
            }
        }

        // Consume the code (one-time use)
        if (!authCodeRepo.consumeCode(systemRealm, code)) {
            return errorResponse("invalid_grant", "Authorization code already consumed");
        }

        // Look up the user's credential to get roles
        Optional<CredentialUserIdPassword> credOpt =
                credentialRepo.findBySubject(authCode.getSubject(), systemRealm, true);
        if (credOpt.isEmpty()) {
            return errorResponse("server_error", "User credential not found");
        }

        return issueTokens(credOpt.get());
    }

    private Response handleClientCredentials(OAuthClient client) {
        // For client_credentials, the client itself acts as the principal.
        // Look up a credential linked to this OAuth client.
        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<CredentialUserIdPassword> credOpt =
                credentialRepo.findByUserId(client.getClientId(), systemRealm, true);
        if (credOpt.isEmpty()) {
            return errorResponse("invalid_client",
                    "No credential linked to this client. Create a credential with userId matching the client_id.");
        }
        return issueTokens(credOpt.get());
    }

    private Response handleRefreshToken(OAuthClient client, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return errorResponse("invalid_request", "refresh_token is required");
        }
        try {
            AuthProvider provider = authProviderFactory.getAuthProvider();
            AuthProvider.LoginResponse loginResponse = provider.refreshTokens(refreshToken);
            if (loginResponse.authenticated() && loginResponse.positiveResponse() != null) {
                var pos = loginResponse.positiveResponse();
                return tokenResponse(pos.accessToken(), pos.refreshToken(),
                        tokenDuration, "Bearer");
            }
            return errorResponse("invalid_grant", "Refresh token is invalid or expired");
        } catch (Exception e) {
            Log.warn("Refresh token exchange failed", e);
            return errorResponse("invalid_grant", "Refresh token is invalid or expired");
        }
    }

    private Response issueTokens(CredentialUserIdPassword cred) {
        try {
            Set<String> roles = cred.getRoles() != null
                    ? new LinkedHashSet<>(Arrays.asList(cred.getRoles()))
                    : Set.of();
            long expiresAt = TokenUtils.expiresAt(tokenDuration);
            String accessToken = TokenUtils.generateUserToken(
                    cred.getSubject(), roles, expiresAt, issuer);
            String refresh = TokenUtils.generateRefreshToken(
                    cred.getSubject(), tokenDuration * 2, issuer);
            return tokenResponse(accessToken, refresh, tokenDuration, "Bearer");
        } catch (Exception e) {
            Log.error("Failed to issue tokens", e);
            return errorResponse("server_error", "Token generation failed");
        }
    }

    private Response tokenResponse(String accessToken, String refreshToken,
                                   long expiresIn, String tokenType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("token_type", tokenType);
        body.put("expires_in", expiresIn);
        if (refreshToken != null) {
            body.put("refresh_token", refreshToken);
        }
        return Response.ok(body)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .build();
    }

    private Response errorResponse(String error, String description) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        int status = switch (error) {
            case "invalid_client" -> 401;
            case "server_error" -> 500;
            default -> 400;
        };
        return Response.status(status).entity(body)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Resolve client_id and client_secret from either HTTP Basic auth or form params.
     */
    private String[] resolveClientCredentials(String authHeader, String formClientId, String formClientSecret) {
        if (authHeader != null && authHeader.toLowerCase().startsWith("basic ")) {
            String decoded = new String(
                    Base64.getDecoder().decode(authHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon > 0) {
                return new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)};
            }
        }
        return new String[]{formClientId, formClientSecret};
    }

    /**
     * Verify a PKCE code_verifier against the stored code_challenge (S256 method).
     */
    private boolean verifyPkce(String codeVerifier, String codeChallenge) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return computed.equals(codeChallenge);
        } catch (Exception e) {
            Log.warn("PKCE verification error", e);
            return false;
        }
    }
}
