package com.e2eq.framework.system.remote;

import com.e2eq.framework.api.system.SubjectFlatRoles;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.api.IdentityEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.SubjectFlatRolesEntry;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Phase C of the control-plane split: SystemDirectory backed by the
 * control-plane API. This is a pure mapper over the SDK-generated JAX-RS
 * client {@link DefaultEndpoint} (quantum-control-plane-client, generated from
 * control-plane.openapi.yaml) — the HTTP transport is the MicroProfile Rest
 * Client runtime, not hand-written here. Catalog DTOs map to/from
 * {@link Realm}.
 *
 * Deliberate fail-loud surface in remote mode:
 * - {@link #systemRealmId()} — a tier-2 deployment has NO local system realm;
 *   any caller needing the realm string is on a path the control plane owns.
 * - credential lookups — identity is validated via the platform JWKS;
 *   credential material never crosses the tenant->system seam BY DESIGN
 *   (realm-membership ADR / B4). A code path requesting raw credentials in a
 *   tier-2 app is an architecture error to surface, not to serve.
 *
 * The one identity read that does cross the seam is {@link #flatRolesForSubject}
 * (contract 1.1.0, generated {@link IdentityEndpoint}): flat role names and the
 * userId, never credential material. It is service-to-service only, so it is sent
 * with the configured service token ({@code quantum.system-service.token}) and
 * NEVER with the forwarded end-user bearer; with no service token it fails loud.
 * It goes to the identity authority ({@code quantum.system-service.identity-base-url},
 * default the control-plane base URL), which is quantum-auth-service.
 *
 * Flat-role answers (found, disabled and unknown subject alike) are cached per
 * subject for a short TTL (default 30s, {@code quantum.system-service.flat-roles.cache-ttl},
 * 0 disables). Consequence: revoking a flat role or disabling a credential takes
 * effect on realm switches within that TTL. Failures (unreachable, refused,
 * contract violations) are never cached, so an outage fails closed on every call.
 */
public class RemoteSystemDirectory implements SystemDirectory {

    private final DefaultEndpoint client;
    private final String baseUrl;
    private final Supplier<Optional<String>> bearerTokenSupplier;

    // Identity authority (flatRolesForSubject): its own endpoint and the SERVICE token only.
    private final IdentityEndpoint identityClient;
    private final String identityBaseUrl;
    private final Supplier<Optional<String>> serviceTokenSupplier;
    private final long flatRolesTtlNanos;
    private final LongSupplier nanoClock;

    /** Default TTL of the per-subject flat-roles cache; bounds how long a revocation can lag. */
    public static final Duration DEFAULT_FLAT_ROLES_TTL = Duration.ofSeconds(30);
    // Bounded so a stream of distinct subjects cannot grow the cache without limit.
    static final int FLAT_ROLES_CACHE_MAX = 10_000;
    private final Map<String, CachedFlatRoles> flatRolesBySubject = new ConcurrentHashMap<>();

    private record CachedFlatRoles(Optional<SubjectFlatRoles> value, long expiresAtNanos) {
        boolean fresh(long nowNanos) {
            return nowNanos - expiresAtNanos < 0;
        }
    }

    // Realm lookups run on EVERY delegated (X-Realm) request; without caching that is one
    // control-plane HTTP round-trip per request. The realm catalog changes rarely, so a short
    // positive-only TTL cache removes the per-request hop while keeping newly-provisioned realms
    // resolvable immediately (misses are NOT cached, so a realm registered after a miss resolves
    // on the very next lookup). registerRealm refreshes the entry. The realm record is
    // caller-independent, so entries are safely shared across principals.
    private static final long CACHE_TTL_NANOS = 60_000_000_000L; // 60s
    private final Map<String, CachedRealm> byRefName = new ConcurrentHashMap<>();
    private final Map<String, CachedRealm> byEmailDomain = new ConcurrentHashMap<>();

    private record CachedRealm(Realm realm, long expiresAtNanos) {
        boolean fresh(long nowNanos) {
            return nowNanos - expiresAtNanos < 0;
        }
    }

    /** Production: build the SDK client (transport via MP Rest Client). */
    public RemoteSystemDirectory(String baseUrl, Optional<String> bearerToken) {
        this(baseUrl, () -> bearerToken);
    }

    /** Production request-scoped bearer forwarding for authenticated catalog reads. */
    public RemoteSystemDirectory(String baseUrl, Supplier<Optional<String>> bearerTokenSupplier) {
        this(baseUrl, bearerTokenSupplier, null, Optional::empty, DEFAULT_FLAT_ROLES_TTL);
    }

    /**
     * Production, with the identity authority: catalog reads forward the request bearer;
     * {@link #flatRolesForSubject} goes to {@code identityBaseUrl} (null = {@code baseUrl}) with
     * the service token only.
     */
    public RemoteSystemDirectory(String baseUrl, Supplier<Optional<String>> bearerTokenSupplier,
                                 String identityBaseUrl, Supplier<Optional<String>> serviceTokenSupplier,
                                 Duration flatRolesTtl) {
        this.client = null;
        this.baseUrl = baseUrl;
        this.bearerTokenSupplier = bearerTokenSupplier;
        this.identityClient = null;
        this.identityBaseUrl = identityBaseUrl == null || identityBaseUrl.isBlank() ? baseUrl : identityBaseUrl;
        this.serviceTokenSupplier = serviceTokenSupplier == null ? Optional::empty : serviceTokenSupplier;
        this.flatRolesTtlNanos = ttlNanos(flatRolesTtl);
        this.nanoClock = System::nanoTime;
    }

    /** Direct injection of the typed client (tests, alternate transports). */
    public RemoteSystemDirectory(DefaultEndpoint client) {
        this(client, null, DEFAULT_FLAT_ROLES_TTL, System::nanoTime);
    }

    /** Direct injection of both typed clients (tests, alternate transports). */
    public RemoteSystemDirectory(DefaultEndpoint client, IdentityEndpoint identityClient,
                                 Duration flatRolesTtl, LongSupplier nanoClock) {
        this.client = client;
        this.baseUrl = null;
        this.bearerTokenSupplier = null;
        this.identityClient = identityClient;
        this.identityBaseUrl = null;
        this.serviceTokenSupplier = null;
        this.flatRolesTtlNanos = ttlNanos(flatRolesTtl);
        this.nanoClock = nanoClock;
    }

    private static long ttlNanos(Duration ttl) {
        if (ttl == null) {
            return DEFAULT_FLAT_ROLES_TTL.toNanos();
        }
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("flat-roles cache TTL must not be negative: " + ttl);
        }
        return ttl.toNanos();
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
        long now = System.nanoTime();
        CachedRealm hit = byEmailDomain.get(emailDomain);
        if (hit != null && hit.fresh(now)) {
            return Optional.of(hit.realm());
        }
        Optional<Realm> resolved = getRealm(
            () -> client().findRealmByEmailDomain(emailDomain), "realm by email domain " + emailDomain);
        resolved.ifPresent(r -> cache(r, System.nanoTime()));
        return resolved;
    }

    @Override
    public Optional<Realm> findRealmByRefName(String refName) {
        long now = System.nanoTime();
        CachedRealm hit = byRefName.get(refName);
        if (hit != null && hit.fresh(now)) {
            return Optional.of(hit.realm());
        }
        Optional<Realm> resolved = getRealm(() -> client().findRealmByRefName(refName), "realm " + refName);
        resolved.ifPresent(r -> cache(r, System.nanoTime()));
        return resolved;
    }

    @Override
    public Realm registerRealm(Realm realm) {
        try {
            if (realm.getRefName() == null || realm.getRefName().isBlank()) {
                throw new IllegalArgumentException("registerRealm requires realm.refName");
            }
            Realm saved = ControlPlaneRealmMapper.fromEntry(
                client().registerRealm(realm.getRefName(), ControlPlaneRealmMapper.toEntry(realm)));
            cache(saved, System.nanoTime());
            return saved;
        } catch (WebApplicationException e) {
            throw ControlPlaneException.rejected("registering realm " + realm.getRefName(),
                e.getResponse().getStatus(), e);
        } catch (ProcessingException e) {
            throw unreachable("registering realm " + realm.getRefName(), e);
        }
    }

    /** Refresh both lookup caches from a resolved/registered realm. */
    private void cache(Realm realm, long nowNanos) {
        CachedRealm entry = new CachedRealm(realm, nowNanos + CACHE_TTL_NANOS);
        if (realm.getRefName() != null) {
            byRefName.put(realm.getRefName(), entry);
        }
        if (realm.getEmailDomain() != null) {
            byEmailDomain.put(realm.getEmailDomain(), entry);
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

    /**
     * Flat roles of the credential bound to {@code subject}, from the identity authority.
     * Unknown subject (typed 404) is {@code Optional.empty()}; every other failure throws a
     * {@link ControlPlaneException} (an {@link IllegalStateException}) and is never cached.
     */
    @Override
    public Optional<SubjectFlatRoles> flatRolesForSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        long now = nanoClock.getAsLong();
        CachedFlatRoles hit = flatRolesBySubject.get(subject);
        if (hit != null && hit.fresh(now)) {
            return hit.value();
        }
        String what = "flat roles for subject " + subject;
        Optional<SubjectFlatRoles> resolved;
        try {
            resolved = Optional.of(toFlatRoles(subject, identityClient().flatRolesForSubject(subject), what));
        } catch (WebApplicationException e) {
            int status = e.getResponse() == null ? 0 : e.getResponse().getStatus();
            if (status != 404) {
                throw ControlPlaneException.rejected(what, status, e);
            }
            resolved = Optional.empty();
        } catch (ProcessingException e) {
            throw unreachable(what, e);
        }
        if (flatRolesTtlNanos > 0) {
            if (flatRolesBySubject.size() >= FLAT_ROLES_CACHE_MAX) {
                flatRolesBySubject.clear();
            }
            flatRolesBySubject.put(subject, new CachedFlatRoles(resolved, nanoClock.getAsLong() + flatRolesTtlNanos));
        }
        return resolved;
    }

    private static SubjectFlatRoles toFlatRoles(String subject, SubjectFlatRolesEntry entry, String what) {
        if (entry == null) {
            throw ControlPlaneException.contractViolation(what, "empty response body");
        }
        if (!subject.equals(entry.getSubject())) {
            throw ControlPlaneException.contractViolation(what,
                "response subject " + entry.getSubject() + " does not match the request");
        }
        if (entry.getUserId() == null || entry.getUserId().isBlank()
                || entry.getActive() == null || entry.getRoles() == null) {
            throw ControlPlaneException.contractViolation(what, "userId, active and roles are required");
        }
        if (!entry.getActive() && !entry.getRoles().isEmpty()) {
            throw ControlPlaneException.contractViolation(what, "a disabled credential carries no roles");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : entry.getRoles()) {
            if (role != null && !role.isBlank()) {
                roles.add(role.trim());
            }
        }
        return new SubjectFlatRoles(entry.getSubject(), entry.getUserId(), entry.getActive(), roles);
    }

    private IdentityEndpoint identityClient() {
        if (identityClient != null) {
            return identityClient;
        }
        if (identityBaseUrl == null) {
            throw ControlPlaneException.notConfigured("flat roles for subject",
                "quantum.system-service.identity-base-url");
        }
        // Service-to-service only: never the forwarded end-user bearer.
        Optional<String> serviceToken = serviceTokenSupplier.get().filter(token -> !token.isBlank());
        if (serviceToken.isEmpty()) {
            throw ControlPlaneException.notConfigured("flat roles for subject", "quantum.system-service.token");
        }
        return ControlPlaneClientFactory.build(IdentityEndpoint.class, identityBaseUrl, serviceToken);
    }

    private Optional<Realm> getRealm(Supplier<RealmCatalogEntry> call, String what) {
        try {
            return Optional.of(ControlPlaneRealmMapper.fromEntry(call.get()));
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (WebApplicationException e) {
            // Quarkus Rest Client maps HTTP 404 to WebApplicationException, not the
            // server-side NotFoundException the in-process stub throws. A missing
            // catalog row is Optional.empty(). Client errors (400/403) must stay
            // 4xx — wrapping them as IllegalStateException becomes HTTP 500 and
            // Helixor Code retries that as a cold start for 90s.
            int status = e.getResponse() == null ? 0 : e.getResponse().getStatus();
            if (status == 404) {
                return Optional.empty();
            }
            if (status >= 400 && status < 500) {
                throw e;
            }
            throw ControlPlaneException.rejected(what, status, e);
        } catch (ProcessingException e) {
            throw unreachable(what, e);
        }
    }

    private DefaultEndpoint client() {
        if (client != null) {
            return client;
        }
        Optional<String> bearerToken = bearerTokenSupplier == null
            ? Optional.empty()
            : bearerTokenSupplier.get();
        return ControlPlaneClientFactory.build(baseUrl, bearerToken);
    }

    private static ControlPlaneException unreachable(String what, Throwable cause) {
        return ControlPlaneException.unreachable(what, cause);
    }

    private static IllegalStateException credentialLookupsAreControlPlaneInternal() {
        return new IllegalStateException(
            "Credential lookups are control-plane-internal: in remote mode identity is validated "
            + "via the platform JWKS and credential material never crosses the tenant->system seam "
            + "(realm-membership ADR / B4). Port the calling path to JWT-claims-based identity.");
    }

}
