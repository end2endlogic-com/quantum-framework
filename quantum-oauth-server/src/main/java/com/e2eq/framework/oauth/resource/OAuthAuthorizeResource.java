package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.oauth.model.AuthorizationCode;
import com.e2eq.framework.oauth.model.OAuthClient;
import com.e2eq.framework.oauth.repo.AuthorizationCodeRepo;
import com.e2eq.framework.oauth.repo.OAuthClientRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OAuth 2.0 Authorization endpoint.
 * <p>
 * GET  /oauth/authorize — presents a login form to the user
 * POST /oauth/authorize — validates credentials and redirects with an authorization code
 * <p>
 * Supports Authorization Code flow with PKCE (S256).
 */
@Path("/oauth/authorize")
@PermitAll
public class OAuthAuthorizeResource {

    @Inject
    OAuthClientRepo oauthClientRepo;

    @Inject
    AuthorizationCodeRepo authCodeRepo;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    /**
     * GET — Validate the authorization request and present a login form.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response authorize(
            @QueryParam("response_type") String responseType,
            @QueryParam("client_id") String clientId,
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("scope") String scope,
            @QueryParam("state") String state,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") String codeChallengeMethod) {

        // Validate request
        if (!"code".equals(responseType)) {
            return errorPage("Unsupported response_type. Only 'code' is supported.");
        }

        if (clientId == null || clientId.isBlank()) {
            return errorPage("client_id is required.");
        }

        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<OAuthClient> clientOpt = oauthClientRepo.findByClientId(systemRealm, clientId);
        if (clientOpt.isEmpty()) {
            return errorPage("Unknown client_id.");
        }

        OAuthClient client = clientOpt.get();

        if (redirectUri == null || redirectUri.isBlank()) {
            return errorPage("redirect_uri is required.");
        }

        if (!client.getRedirectUris().contains(redirectUri)) {
            return errorPage("redirect_uri is not registered for this client.");
        }

        if (codeChallengeMethod != null && !"S256".equals(codeChallengeMethod)) {
            return redirectError(redirectUri, state, "invalid_request",
                    "Only S256 code_challenge_method is supported");
        }

        // Render the login form
        String html = buildLoginForm(clientId, redirectUri, scope, state, codeChallenge, codeChallengeMethod);
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    /**
     * POST — Authenticate the user and redirect with an authorization code.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response login(
            @FormParam("username") String username,
            @FormParam("password") String password,
            @FormParam("client_id") String clientId,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("scope") String scope,
            @FormParam("state") String state,
            @FormParam("code_challenge") String codeChallenge,
            @FormParam("code_challenge_method") String codeChallengeMethod) {

        // Re-validate client
        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<OAuthClient> clientOpt = oauthClientRepo.findByClientId(systemRealm, clientId);
        if (clientOpt.isEmpty()) {
            return errorPage("Unknown client_id.");
        }

        OAuthClient client = clientOpt.get();

        if (!client.getRedirectUris().contains(redirectUri)) {
            return errorPage("redirect_uri mismatch.");
        }

        // Authenticate user via the default auth provider
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return errorPage("Username and password are required.");
        }

        try {
            AuthProvider provider = authProviderFactory.getAuthProvider();
            AuthProvider.LoginResponse loginResponse = provider.login(username, password);

            if (!loginResponse.authenticated() || loginResponse.positiveResponse() == null) {
                String msg = loginResponse.negativeResponse() != null
                        ? loginResponse.negativeResponse().errorMessage()
                        : "Invalid credentials";
                return errorPage("Login failed: " + msg);
            }

            // Find the credential to get the subject
            Optional<CredentialUserIdPassword> credOpt =
                    credentialRepo.findByUserId(username, systemRealm, true);
            if (credOpt.isEmpty()) {
                return errorPage("User credential not found.");
            }

            CredentialUserIdPassword cred = credOpt.get();

            // Generate authorization code
            String code = UUID.randomUUID().toString().replace("-", "");
            List<String> scopes = scope != null ? Arrays.asList(scope.split("\\s+")) : List.of("openid");

            AuthorizationCode authCode = AuthorizationCode.builder()
                    .id(new ObjectId())
                    .refName(code)
                    .displayName("OAuth authorization code")
                    .code(code)
                    .clientId(clientId)
                    .subject(cred.getSubject())
                    .userId(cred.getUserId())
                    .redirectUri(redirectUri)
                    .codeChallenge(codeChallenge)
                    .codeChallengeMethod(codeChallengeMethod)
                    .scopes(scopes)
                    .realm(client.getRealm() != null ? client.getRealm() : systemRealm)
                    .consumed(false)
                    .expiresAt(new Date(System.currentTimeMillis() + 5 * 60 * 1000)) // 5 minutes
                    .build();

            // Persist the authorization code
            var ds = morphiaDataStore(systemRealm);
            ds.save(authCode);

            // Redirect back to the client with the code
            StringBuilder redirect = new StringBuilder(redirectUri);
            redirect.append(redirectUri.contains("?") ? "&" : "?");
            redirect.append("code=").append(encode(code));
            if (state != null) {
                redirect.append("&state=").append(encode(state));
            }

            return Response.seeOther(URI.create(redirect.toString())).build();

        } catch (Exception e) {
            Log.error("Authorization failed", e);
            return redirectError(redirectUri, state, "server_error", "Authentication failed");
        }
    }

    private dev.morphia.Datastore morphiaDataStore(String realm) {
        return authCodeRepo.getMorphiaDataStoreWrapper().getDataStore(realm);
    }

    private Response redirectError(String redirectUri, String state, String error, String description) {
        StringBuilder redirect = new StringBuilder(redirectUri);
        redirect.append(redirectUri.contains("?") ? "&" : "?");
        redirect.append("error=").append(encode(error));
        redirect.append("&error_description=").append(encode(description));
        if (state != null) {
            redirect.append("&state=").append(encode(state));
        }
        return Response.seeOther(URI.create(redirect.toString())).build();
    }

    private Response errorPage(String message) {
        String html = """
                <!DOCTYPE html>
                <html>
                <head><title>Authorization Error</title>
                <style>body{font-family:system-ui,sans-serif;max-width:500px;margin:80px auto;padding:0 20px}
                .error{color:#c00;background:#fee;border:1px solid #fcc;padding:16px;border-radius:8px}</style>
                </head>
                <body>
                <h2>Authorization Error</h2>
                <div class="error">%s</div>
                </body>
                </html>
                """.formatted(escapeHtml(message));
        return Response.status(400).entity(html).type(MediaType.TEXT_HTML).build();
    }

    private String buildLoginForm(String clientId, String redirectUri, String scope,
                                  String state, String codeChallenge, String codeChallengeMethod) {
        return """
                <!DOCTYPE html>
                <html>
                <head><title>Sign In</title>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                body{font-family:system-ui,sans-serif;max-width:400px;margin:80px auto;padding:0 20px}
                h2{text-align:center;color:#333}
                form{background:#f8f9fa;padding:24px;border-radius:12px;border:1px solid #dee2e6}
                label{display:block;margin-bottom:4px;font-weight:600;color:#495057}
                input[type=text],input[type=password]{width:100%%;box-sizing:border-box;padding:10px;
                  margin-bottom:16px;border:1px solid #ced4da;border-radius:6px;font-size:14px}
                button{width:100%%;padding:12px;background:#0d6efd;color:#fff;border:none;border-radius:6px;
                  font-size:16px;cursor:pointer}
                button:hover{background:#0b5ed7}
                </style>
                </head>
                <body>
                <h2>Sign In</h2>
                <form method="POST" action="/oauth/authorize">
                  <input type="hidden" name="client_id" value="%s">
                  <input type="hidden" name="redirect_uri" value="%s">
                  <input type="hidden" name="scope" value="%s">
                  <input type="hidden" name="state" value="%s">
                  <input type="hidden" name="code_challenge" value="%s">
                  <input type="hidden" name="code_challenge_method" value="%s">
                  <label for="username">Username</label>
                  <input type="text" id="username" name="username" required autofocus>
                  <label for="password">Password</label>
                  <input type="password" id="password" name="password" required>
                  <button type="submit">Sign In</button>
                </form>
                </body>
                </html>
                """.formatted(
                escapeHtml(clientId),
                escapeHtml(redirectUri),
                escapeHtml(scope != null ? scope : "openid"),
                escapeHtml(state != null ? state : ""),
                escapeHtml(codeChallenge != null ? codeChallenge : ""),
                escapeHtml(codeChallengeMethod != null ? codeChallengeMethod : ""));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
