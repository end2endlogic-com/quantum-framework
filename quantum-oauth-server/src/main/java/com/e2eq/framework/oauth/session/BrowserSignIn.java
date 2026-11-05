package com.e2eq.framework.oauth.session;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.oauth.model.OAuthClient;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Browser sign-in is an authentication proof only. Code exchange still performs all application authorization. */
@ApplicationScoped
public class BrowserSignIn {
    public static final String SESSION_COOKIE = "__Host-quantum-oauth-sso";
    public static final String BROWSER_COOKIE = "__Host-quantum-oauth-browser";
    @ConfigProperty(name = "quantum.oauth.browser-sso.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "quantum.oauth.browser-sso.clients") Optional<List<String>> clients;
    @ConfigProperty(name = "quantum.oauth.browser-sso.max-age-seconds", defaultValue = "28800") long maximumAge;
    @ConfigProperty(name = "mp.jwt.verify.issuer") Optional<String> issuer;
    @Inject BrowserSessionStore store;
    @Inject CredentialRepo credentials;
    @Inject AuthProviderFactory providers;
    @Inject EnvConfigUtils environment;
    Clock clock = Clock.systemUTC();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Request(String client, String redirect, String scope, String state, String challenge, String method) {
        public String fingerprint() {
            var text = new StringBuilder();
            for (String value : new String[]{client, redirect, scope, state, challenge, method}) {
                String v = value == null ? "" : value;
                text.append(v.length()).append(':').append(v);
            }
            return hash(text.toString());
        }
    }
    public record LoginForm(String proof, String browser) { }
    public record Authenticated(CredentialUserIdPassword credential, long revision, java.time.Instant expiresAt) { }

    void start(@Observes StartupEvent event) {
        if (!enabled) return;
        URI origin = URI.create(issuer.orElseThrow(() -> new IllegalStateException("BROWSER_SSO_ISSUER_REQUIRED")));
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || maximumAge < 60 || maximumAge > 28800
                || clients.isEmpty() || clients.get().isEmpty() || clients.get().stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("BROWSER_SSO_CONFIGURATION_INVALID: HTTPS issuer, explicit clients and lifetime 60..28800 required");
        }
        store.initialize();
    }
    public boolean enabled() { return enabled; }
    public boolean applies(String client) { return enabled && clients.orElse(List.of()).contains(client); }

    public void requireOrigin(UriInfo uri) {
        URI expected = URI.create(issuer.orElseThrow());
        URI actual = uri.getBaseUri();
        if (!"https".equals(actual.getScheme()) || !expected.getHost().equalsIgnoreCase(actual.getHost())
                || port(expected) != port(actual)) throw new ForbiddenException("BROWSER_SSO_ORIGIN_MISMATCH");
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    public void validateScope(OAuthClient client, String scope) {
        if (scope == null || scope.isBlank()) return;
        for (String requested : scope.trim().split("\\s+")) {
            if ("openid".equals(requested) || client.getAllowedScopes() == null || !client.getAllowedScopes().contains(requested))
                throw new BadRequestException("BROWSER_SSO_SCOPE_UNSUPPORTED");
        }
    }
    public LoginForm form(HttpHeaders headers, Request request) {
        String browser = cookie(headers, BROWSER_COOKIE);
        if (!validOpaque(browser)) browser = random();
        String proof = random();
        store.saveLogin(hash(proof), issuer.orElseThrow(), hash(browser), request.fingerprint(), clock.instant().plusSeconds(300));
        return new LoginForm(proof, browser);
    }
    public void consumeLogin(HttpHeaders headers, Request request, String proof) {
        String browser = cookie(headers, BROWSER_COOKIE);
        if (!validOpaque(proof) || !validOpaque(browser)
                || !store.consumeLogin(hash(proof), issuer.orElseThrow(), hash(browser), request.fingerprint(), clock.instant()))
            throw new ForbiddenException("BROWSER_SSO_LOGIN_PROOF_INVALID");
    }
    public Optional<Authenticated> resume(HttpHeaders headers) {
        String token = cookie(headers, SESSION_COOKIE);
        if (!validOpaque(token)) return Optional.empty();
        var saved = store.session(hash(token), issuer.orElseThrow(), clock.instant());
        if (saved.isEmpty()) return Optional.empty();
        var session = saved.get();
        var credential = credentials.findBySubject(session.subject(), environment.getSystemRealm(), true);
        if (credential.isEmpty() || !usable(credential.get())
                || !session.userId().equals(credential.get().getUserId())
                || session.passwordRevision() != credential.get().currentPasswordRevision()
                || !session.provider().equals(providers.getAuthProvider().getName())) {
            store.revoke(hash(token), issuer.orElseThrow());
            return Optional.empty();
        }
        return Optional.of(new Authenticated(credential.get(), session.passwordRevision(), session.expiresAt()));
    }
    public String create(HttpHeaders headers, CredentialUserIdPassword credential, long revision, String provider) {
        if (!usable(credential) || credential.currentPasswordRevision() != revision || provider == null || provider.isBlank())
            throw new ForbiddenException("BROWSER_SSO_CREDENTIAL_CHANGED");
        String token = random();
        store.saveSession(hash(token), issuer.orElseThrow(), new BrowserSessionStore.Session(credential.getSubject(),
                credential.getUserId(), revision, provider, clock.instant().plusSeconds(maximumAge)));
        revoke(headers); // Rotate any previous browser session after successful explicit authentication.
        return token;
    }
    public Optional<BrowserSessionStore.Session> session(String token) {
        return validOpaque(token) ? store.session(hash(token), issuer.orElseThrow(), clock.instant()) : Optional.empty();
    }
    public boolean codeSessionActive(String hash, String subject, Long revision) {
        return issuer.isPresent() && store.session(hash, issuer.get(), clock.instant())
                .filter(s -> s.subject().equals(subject) && revision != null && s.passwordRevision() == revision).isPresent();
    }
    public void revoke(HttpHeaders headers) {
        String token = cookie(headers, SESSION_COOKIE);
        if (validOpaque(token)) store.revoke(hash(token), issuer.orElseThrow());
    }
    public String logoutProof(HttpHeaders headers) {
        String token = cookie(headers, SESSION_COOKIE);
        if (!validOpaque(token)) throw new ForbiddenException("BROWSER_SSO_SESSION_REQUIRED");
        return hash("logout:" + issuer.orElseThrow() + ":" + token);
    }
    public void logout(HttpHeaders headers, String proof) {
        if (proof == null || !MessageDigest.isEqual(logoutProof(headers).getBytes(StandardCharsets.US_ASCII),
                proof.getBytes(StandardCharsets.US_ASCII))) throw new ForbiddenException("BROWSER_SSO_LOGOUT_PROOF_INVALID");
        revoke(headers);
    }
    public String sessionCookie(String value) { return cookieHeader(SESSION_COOKIE, value, maximumAge); }
    public static String cookieHeader(String name, String value, long age) {
        return name + "=" + value + "; Path=/; Max-Age=" + age + "; Secure; HttpOnly; SameSite=Lax";
    }
    public static String cookie(HttpHeaders headers, String name) {
        var cookie = headers == null ? null : headers.getCookies().get(name);
        return cookie == null ? null : cookie.getValue();
    }
    private static boolean usable(CredentialUserIdPassword c) {
        return c.getSubject() != null && c.getUserId() != null && c.getActiveStatus() != ActiveStatus.INACTIVE
                && c.getActiveStatus() != ActiveStatus.DELETED && !Boolean.TRUE.equals(c.getForceChangePassword());
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean validOpaque(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
}
