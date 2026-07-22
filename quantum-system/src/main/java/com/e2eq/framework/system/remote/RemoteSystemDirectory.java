package com.e2eq.framework.system.remote;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
 */
public class RemoteSystemDirectory implements SystemDirectory {

    private final DefaultEndpoint client;
    private final String baseUrl;
    private final Supplier<Optional<String>> bearerTokenSupplier;

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
        this.client = null;
        this.baseUrl = baseUrl;
        this.bearerTokenSupplier = bearerTokenSupplier;
    }

    /** Direct injection of the typed client (tests, alternate transports). */
    public RemoteSystemDirectory(DefaultEndpoint client) {
        this.client = client;
        this.baseUrl = null;
        this.bearerTokenSupplier = null;
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
            Realm saved = ControlPlaneRealmMapper.fromEntry(
                client().registerRealm(ControlPlaneRealmMapper.toEntry(realm)));
            cache(saved, System.nanoTime());
            return saved;
        } catch (WebApplicationException e) {
            throw new IllegalStateException("Control plane rejected realm registration for "
                + realm.getRefName() + ": HTTP " + e.getResponse().getStatus(), e);
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

    private Optional<Realm> getRealm(Supplier<RealmCatalogEntry> call, String what) {
        try {
            return Optional.of(ControlPlaneRealmMapper.fromEntry(call.get()));
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (WebApplicationException e) {
            throw new IllegalStateException("Control plane returned HTTP "
                + e.getResponse().getStatus() + " for " + what, e);
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

    private static IllegalStateException unreachable(String what, Throwable cause) {
        return new IllegalStateException(
            "Control plane unreachable for " + what + " — failing loud, no local fallback.", cause);
    }

    private static IllegalStateException credentialLookupsAreControlPlaneInternal() {
        return new IllegalStateException(
            "Credential lookups are control-plane-internal: in remote mode identity is validated "
            + "via the platform JWKS and credential material never crosses the tenant->system seam "
            + "(realm-membership ADR / B4). Port the calling path to JWT-claims-based identity.");
    }

}
