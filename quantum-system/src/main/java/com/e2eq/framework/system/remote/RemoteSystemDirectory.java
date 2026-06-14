package com.e2eq.framework.system.remote;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;
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

    /** Production: build the SDK client (transport via MP Rest Client). */
    public RemoteSystemDirectory(String baseUrl, Optional<String> bearerToken) {
        this(ControlPlaneClientFactory.build(baseUrl, bearerToken));
    }

    /** Direct injection of the typed client (tests, alternate transports). */
    public RemoteSystemDirectory(DefaultEndpoint client) {
        this.client = client;
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
        return getRealm(() -> client.findRealmByEmailDomain(emailDomain), "realm by email domain " + emailDomain);
    }

    @Override
    public Optional<Realm> findRealmByRefName(String refName) {
        return getRealm(() -> client.findRealmByRefName(refName), "realm " + refName);
    }

    @Override
    public Realm registerRealm(Realm realm) {
        try {
            return fromEntry(client.registerRealm(toEntry(realm)));
        } catch (WebApplicationException e) {
            throw new IllegalStateException("Control plane rejected realm registration for "
                + realm.getRefName() + ": HTTP " + e.getResponse().getStatus(), e);
        } catch (ProcessingException e) {
            throw unreachable("registering realm " + realm.getRefName(), e);
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
            return Optional.of(fromEntry(call.get()));
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (WebApplicationException e) {
            throw new IllegalStateException("Control plane returned HTTP "
                + e.getResponse().getStatus() + " for " + what, e);
        } catch (ProcessingException e) {
            throw unreachable(what, e);
        }
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

    /** Contract mapping: RealmCatalogEntry <-> Realm (catalog fields only). */
    static Realm fromEntry(RealmCatalogEntry entry) {
        Realm realm = new Realm();
        realm.setRefName(entry.getRefName());
        realm.setDisplayName(entry.getDisplayName());
        realm.setDatabaseName(entry.getDatabaseName());
        realm.setEmailDomain(entry.getEmailDomain());
        realm.setConnectionString(entry.getConnectionString());
        return realm;
    }

    static RealmCatalogEntry toEntry(Realm realm) {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName(realm.getRefName());
        entry.setDisplayName(realm.getDisplayName());
        entry.setDatabaseName(realm.getDatabaseName());
        entry.setEmailDomain(realm.getEmailDomain());
        entry.setConnectionString(realm.getConnectionString());
        return entry;
    }
}
