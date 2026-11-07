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

    @Inject
    com.e2eq.framework.oauth.session.BrowserSignIn browserSignIn;

    @jakarta.ws.rs.core.Context
    jakarta.ws.rs.core.HttpHeaders requestHeaders;

    @jakarta.ws.rs.core.Context
    jakarta.ws.rs.core.UriInfo uriInfo;

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

        String validationError = OAuthRequestValidation.authorizationError(client, codeChallenge, codeChallengeMethod);
        if (validationError != null) {
            return redirectError(redirectUri, state, "invalid_request", validationError);
        }

        if (browserSignIn != null && browserSignIn.applies(clientId)) {
            browserSignIn.requireOrigin(uriInfo);
            browserSignIn.validateScope(client, scope);
            var request = new com.e2eq.framework.oauth.session.BrowserSignIn.Request(clientId, redirectUri, scope,
                    state, codeChallenge, codeChallengeMethod);
            var session = browserSignIn.resume(requestHeaders);
            if (session.isPresent()) {
                var authenticated = session.get();
                return browserCode(client, request, authenticated.credential(), authenticated.revision(),
                        authenticated.expiresAt(), com.e2eq.framework.oauth.session.BrowserSignIn.cookie(requestHeaders,
                                com.e2eq.framework.oauth.session.BrowserSignIn.SESSION_COOKIE), false);
            }
            var form = browserSignIn.form(requestHeaders, request);
            String html = buildLoginForm(clientId, redirectUri, scope == null ? "" : scope, state,
                    codeChallenge, codeChallengeMethod).replace("<form method=", "<form autocomplete=\"on\" method=")
                    .replace("<label for=\"username\">", "<input type=\"hidden\" name=\"csrf\" value=\"" + form.proof() + "\">\n<label for=\"username\">");
            return browserHeaders(Response.ok(html, MediaType.TEXT_HTML))
                    .header("Set-Cookie", com.e2eq.framework.oauth.session.BrowserSignIn.cookieHeader(
                            com.e2eq.framework.oauth.session.BrowserSignIn.BROWSER_COOKIE, form.browser(), 300))
                    .header("Set-Cookie", com.e2eq.framework.oauth.session.BrowserSignIn.cookieHeader(
                            com.e2eq.framework.oauth.session.BrowserSignIn.SESSION_COOKIE, "", 0)).build();
        }

        // Render the login form
        String html = buildLoginForm(clientId, redirectUri, scope, state, codeChallenge, codeChallengeMethod);
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    /**
     * POST — Authenticate the user and redirect with an authorization code.
     */
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

        String validationError = OAuthRequestValidation.authorizationError(client, codeChallenge, codeChallengeMethod);
        if (validationError != null) {
            return redirectError(redirectUri, state, "invalid_request", validationError);
        }

        // Authenticate user via the default auth provider
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return errorPage("Username and password are required.");
        }

        try {
            // Read before authenticating: the revision then can only be older than the hash the
            // login checks, never newer, so a reset racing this request still voids the code.
            Long passwordRevisionBeforeLogin = credentialRepo.findByUserId(username, systemRealm, true)
                    .map(CredentialUserIdPassword::currentPasswordRevision)
                    .orElse(null);
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
                    .passwordRevision(passwordRevisionBeforeLogin)
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

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response browserLogin(@FormParam("username") String username, @FormParam("password") String password,
            @FormParam("client_id") String clientId, @FormParam("redirect_uri") String redirectUri,
            @FormParam("scope") String scope, @FormParam("state") String state,
            @FormParam("code_challenge") String challenge, @FormParam("code_challenge_method") String method,
            @FormParam("csrf") String csrf) {
        if (browserSignIn == null || !browserSignIn.applies(clientId))
            return login(username, password, clientId, redirectUri, scope, state, challenge, method);
        browserSignIn.requireOrigin(uriInfo);
        var found = oauthClientRepo.findByClientId(envConfigUtils.getSystemRealm(), clientId);
        if (found.isEmpty() || redirectUri == null || !found.get().getRedirectUris().contains(redirectUri))
            return browserHeaders(Response.status(400).entity("Invalid client or redirect")).build();
        OAuthClient client = found.get();
        if (OAuthRequestValidation.authorizationError(client, challenge, method) != null)
            return browserHeaders(Response.fromResponse(redirectError(redirectUri, state, "invalid_request", "Invalid authorization request"))).build();
        browserSignIn.validateScope(client, scope);
        var request = new com.e2eq.framework.oauth.session.BrowserSignIn.Request(clientId, redirectUri, scope, state, challenge, method);
        browserSignIn.consumeLogin(requestHeaders, request, csrf);
        if (username == null || username.isBlank() || password == null || password.isBlank())
            return browserHeaders(Response.status(400).entity("Credentials required; restart authorization")).build();
        String system = envConfigUtils.getSystemRealm();
        var before = credentialRepo.findByUserId(username, system, true);
        if (before.isEmpty()) return browserHeaders(Response.status(401).entity("Invalid credentials")).build();
        String subject = before.get().getSubject();
        long revision = before.get().currentPasswordRevision();
        var provider = authProviderFactory.getAuthProvider();
        String application = client.getApplicationId() == null || client.getApplicationId().isBlank()
                ? client.getClientId() : client.getApplicationId();
        var result = provider.login(username, password, application, client.getRealm());
        if (!result.authenticated() || result.positiveResponse() == null)
            return browserHeaders(Response.status(401).entity("Authentication or application admission failed; restart authorization")).build();
        var after = credentialRepo.findBySubject(subject, system, true).orElseThrow(() -> new ForbiddenException("Credential changed"));
        if (!after.getUserId().equals(result.positiveResponse().userId())) throw new ForbiddenException("Credential binding changed");
        String cookie = browserSignIn.create(requestHeaders, after, revision, provider.getName());
        var authenticated = browserSignIn.session(cookie).orElseThrow();
        return browserCode(client, request, after, revision, authenticated.expiresAt(), cookie, true);
    }

    private Response browserCode(OAuthClient client, com.e2eq.framework.oauth.session.BrowserSignIn.Request request,
            CredentialUserIdPassword credential, long revision, java.time.Instant sessionExpiresAt,
            String sessionCookie, boolean newlyAuthenticated) {
        String code = UUID.randomUUID().toString().replace("-", "");
        java.time.Instant deadline = java.time.Instant.now().plusSeconds(300);
        if (sessionExpiresAt.isBefore(deadline)) deadline = sessionExpiresAt;
        var authCode = AuthorizationCode.builder().id(new ObjectId()).refName(code).displayName("OAuth authorization code")
                .code(code).clientId(client.getClientId()).subject(credential.getSubject()).userId(credential.getUserId())
                .passwordRevision(revision).redirectUri(request.redirect()).codeChallenge(request.challenge())
                .codeChallengeMethod(request.method()).scopes(request.scope() == null || request.scope().isBlank()
                        ? List.of() : Arrays.asList(request.scope().trim().split("\\s+")))
                .realm(client.getRealm() == null ? envConfigUtils.getSystemRealm() : client.getRealm())
                .browserSessionHash(com.e2eq.framework.oauth.session.BrowserSignIn.hash(sessionCookie))
                .consumed(false).expiresAt(Date.from(deadline)).build();
        morphiaDataStore(envConfigUtils.getSystemRealm()).save(authCode);
        String redirect = request.redirect() + (request.redirect().contains("?") ? "&" : "?") + "code=" + encode(code)
                + (request.state() == null ? "" : "&state=" + encode(request.state()));
        var response = browserHeaders(Response.seeOther(URI.create(redirect)));
        if (newlyAuthenticated) response.header("Set-Cookie", browserSignIn.sessionCookie(sessionCookie))
                .header("Set-Cookie", com.e2eq.framework.oauth.session.BrowserSignIn.cookieHeader(
                        com.e2eq.framework.oauth.session.BrowserSignIn.BROWSER_COOKIE, "", 0));
        return response.build();
    }

    private static Response.ResponseBuilder browserHeaders(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer").header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
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
