package com.e2eq.framework.secrets.rest;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.secrets.crypto.EncryptedValue;
import com.e2eq.framework.secrets.crypto.SecretEncryptor;
import com.e2eq.framework.secrets.model.ManagedSecret;
import com.e2eq.framework.secrets.model.ManagedSecretRepo;
import com.e2eq.framework.secrets.rest.dto.ManagedSecretCreateUpdateRequest;
import com.e2eq.framework.secrets.rest.dto.ManagedSecretResponse;
import com.e2eq.framework.secrets.rest.dto.SecretRotationResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@Path("/settings/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"tenantAdmin", "platformAdmin", "admin", "system"})
// Mirrors the ManagedSecret model's mapping. Without a RESOURCE-level mapping the
// SecurityFilter builds an anonymous ResourceContext (action=none) for this path
// and PermissionRuleInterceptor.prePersist fail-closes every save with
// "Persistence callback requires an explicit write action".
@FunctionalMapping(area = "SECURITY", domain = "SECRET")
public class ManagedSecretResource {

    private static final Logger LOG = Logger.getLogger(ManagedSecretResource.class);

    private final ManagedSecretRepo repo;
    private final SecretEncryptor encryptor;

    /**
     * When no KEK is configured, controls whether the store degrades to
     * plaintext (dev/bootstrap) or fails closed (deployed). Defaults to
     * {@code false} — a misconfigured deployment MUST NOT silently store or
     * return secrets in plaintext. Set {@code true} only in the local/dev
     * profile (e.g. {@code %dev.quantum.secrets.allow-plaintext-fallback=true})
     * to keep the pre-KEK bootstrap experience working.
     */
    @ConfigProperty(name = "quantum.secrets.allow-plaintext-fallback", defaultValue = "false")
    boolean allowPlaintextFallback;

    public ManagedSecretResource(ManagedSecretRepo repo, SecretEncryptor encryptor) {
        this.repo = repo;
        this.encryptor = encryptor;
    }

    /**
     * Fail-closed response for when a secret would be stored or returned in
     * plaintext but the policy forbids it (no KEK + fallback disabled).
     */
    private WebApplicationException plaintextForbidden(String action) {
        LOG.errorf("Refusing to %s secret in plaintext: no KEK configured and "
                + "quantum.secrets.allow-plaintext-fallback is false. Configure "
                + "quantum.secrets.kek.v1 (a base64 256-bit key).", action);
        return new WebApplicationException(
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of("error",
                                "Encryption keys not configured — refusing to " + action
                                        + " secret in plaintext. Configure quantum.secrets.kek.v1."))
                        .build());
    }

    @GET
    @FunctionalAction("LIST")
    public Response list(@QueryParam("type") String secretType) {
        String realmId = repo.getSecurityContextRealmId();
        List<ManagedSecretResponse> response = repo.findAll(realmId, secretType)
                .stream()
                .map(ManagedSecretResource::toResponse)
                .toList();
        return Response.ok(response).build();
    }

    @GET
    @Path("{refName}")
    @FunctionalAction("VIEW")
    public Response get(@PathParam("refName") String refName) {
        String realmId = repo.getSecurityContextRealmId();
        ManagedSecret secret = repo.findByRefName(realmId, refName)
                .orElseThrow(() -> new NotFoundException("Secret not found: " + refName));
        return Response.ok(toResponse(secret)).build();
    }

    /**
     * Returns the decrypted plaintext value of a secret.
     * Intended for internal service consumption only.
     */
    @GET
    @Path("{refName}/value")
    @RolesAllowed({"tenantAdmin", "platformAdmin", "admin", "system"})
    @FunctionalAction("VIEW")
    public Response getValue(@PathParam("refName") String refName) {
        String realmId = repo.getSecurityContextRealmId();
        ManagedSecret secret = repo.findByRefName(realmId, refName)
                .orElseThrow(() -> new NotFoundException("Secret not found: " + refName));

        if (!secret.isConfigured()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Secret has no value configured"))
                    .build();
        }

        // Legacy plaintext secret (pre-migration): iv is null, keyVersion is 0.
        // Only surfaced when the plaintext fallback is explicitly allowed
        // (dev/bootstrap); otherwise fail closed so the value is re-saved under
        // a KEK rather than leaked from plaintext-at-rest.
        if (secret.getIv() == null || secret.getIv().isEmpty()) {
            if (allowPlaintextFallback) {
                return Response.ok(Map.of("value", secret.getValueEncrypted())).build();
            }
            throw plaintextForbidden("return");
        }

        if (!encryptor.isKeysAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Encryption keys not configured — cannot decrypt secret"))
                    .build();
        }

        String plaintext = encryptor.decrypt(
                secret.getValueEncrypted(),
                secret.getIv(),
                secret.getKeyVersion()
        );

        return Response.ok(Map.of("value", plaintext)).build();
    }

    @POST
    @FunctionalAction("CREATE")
    public Response create(ManagedSecretCreateUpdateRequest request) {
        if (request == null || request.getRefName() == null || request.getRefName().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("refName is required").build();
        }
        String realmId = repo.getSecurityContextRealmId();
        if (repo.findByRefName(realmId, request.getRefName()).isPresent()) {
            return Response.status(Response.Status.CONFLICT).entity("Secret already exists: " + request.getRefName()).build();
        }
        ManagedSecret secret = new ManagedSecret();
        apply(secret, request, true);
        repo.save(realmId, secret);
        return Response.status(Response.Status.CREATED).entity(toResponse(secret)).build();
    }

    @PUT
    @Path("{refName}")
    @FunctionalAction("UPDATE")
    public Response update(@PathParam("refName") String refName, ManagedSecretCreateUpdateRequest request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String realmId = repo.getSecurityContextRealmId();
        ManagedSecret secret = repo.findByRefName(realmId, refName)
                .orElseThrow(() -> new NotFoundException("Secret not found: " + refName));
        apply(secret, request, false);
        repo.save(realmId, secret);
        return Response.ok(toResponse(secret)).build();
    }

    @DELETE
    @Path("{refName}")
    @FunctionalAction("DELETE")
    public Response delete(@PathParam("refName") String refName) {
        String realmId = repo.getSecurityContextRealmId();
        if (!repo.deleteByRefName(realmId, refName)) {
            throw new NotFoundException("Secret not found: " + refName);
        }
        return Response.noContent().build();
    }

    /**
     * Re-encrypts all secrets in the current realm from their current key version
     * to the active key version. Secrets already on the active version are skipped.
     */
    @POST
    @Path("rotate-keys")
    @RolesAllowed({"platformAdmin", "admin", "system"})
    @FunctionalAction("UPDATE")
    public Response rotateKeys() {
        if (!encryptor.isKeysAvailable()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Encryption keys not configured — cannot rotate"))
                    .build();
        }

        String realmId = repo.getSecurityContextRealmId();
        int activeVersion = encryptor.getActiveKeyVersion();

        List<ManagedSecret> allSecrets = repo.findAll(realmId, null);

        int rotated = 0;
        int skipped = 0;
        int failed = 0;

        for (ManagedSecret secret : allSecrets) {
            if (!secret.isConfigured()) {
                skipped++;
                continue;
            }

            if (secret.getKeyVersion() == activeVersion) {
                skipped++;
                continue;
            }

            try {
                EncryptedValue reEncrypted = encryptor.reEncrypt(
                        secret.getValueEncrypted(),
                        secret.getIv(),
                        secret.getKeyVersion(),
                        activeVersion
                );
                secret.setValueEncrypted(reEncrypted.getCiphertext());
                secret.setIv(reEncrypted.getIv());
                secret.setKeyVersion(reEncrypted.getKeyVersion());
                repo.save(realmId, secret);
                rotated++;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to rotate secret '%s' from key v%d to v%d",
                        secret.getRefName(), secret.getKeyVersion(), activeVersion);
                failed++;
            }
        }

        LOG.infof("Key rotation complete for realm '%s': rotated=%d, skipped=%d, failed=%d, activeVersion=%d",
                realmId, rotated, skipped, failed, activeVersion);

        SecretRotationResponse result = SecretRotationResponse.builder()
                .rotated(rotated)
                .skipped(skipped)
                .failed(failed)
                .activeKeyVersion(activeVersion)
                .build();

        return Response.ok(result).build();
    }

    // ---- helpers ----

    private static ManagedSecretResponse toResponse(ManagedSecret secret) {
        return ManagedSecretResponse.builder()
                .refName(secret.getRefName())
                .secretType(secret.getSecretType())
                .displayName(secret.getDisplayName())
                .description(secret.getDescription())
                .providerType(secret.getProviderType())
                .realmDefault(secret.isRealmDefault())
                .configured(secret.isConfigured())
                .keyVersion(secret.isConfigured() ? secret.getKeyVersion() : null)
                .build();
    }

    private void apply(ManagedSecret secret, ManagedSecretCreateUpdateRequest request, boolean create) {
        if (create && request.getRefName() != null) {
            secret.setRefName(request.getRefName());
        }
        if (request.getSecretType() != null) {
            secret.setSecretType(request.getSecretType());
        }
        if (request.getDisplayName() != null) {
            secret.setDisplayName(request.getDisplayName());
        }
        if (request.getDescription() != null) {
            secret.setDescription(request.getDescription());
        }
        if (request.getProviderType() != null) {
            secret.setProviderType(request.getProviderType());
        }
        if (request.getRealmDefault() != null) {
            secret.setRealmDefault(request.getRealmDefault());
        }
        if (request.getValue() != null && !request.getValue().isBlank()) {
            if (encryptor.isKeysAvailable()) {
                EncryptedValue encrypted = encryptor.encrypt(request.getValue());
                secret.setValueEncrypted(encrypted.getCiphertext());
                secret.setIv(encrypted.getIv());
                secret.setKeyVersion(encrypted.getKeyVersion());
            } else if (allowPlaintextFallback) {
                // No KEKs configured — dev/bootstrap only. Store plaintext and
                // warn; re-save after configuring quantum.secrets.kek.v1.
                LOG.warn("KEKs not configured — storing secret as plaintext "
                        + "(quantum.secrets.allow-plaintext-fallback=true). "
                        + "Run migration after configuring quantum.secrets.kek.v1");
                secret.setValueEncrypted(request.getValue());
                secret.setIv(null);
                secret.setKeyVersion(0);
            } else {
                // Deployed profile with no KEK — fail closed rather than silently
                // persisting a cleartext secret.
                throw plaintextForbidden("store");
            }
        }
    }
}
