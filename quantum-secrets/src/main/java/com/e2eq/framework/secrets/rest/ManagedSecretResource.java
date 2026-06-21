package com.e2eq.framework.secrets.rest;

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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@Path("/settings/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"tenantAdmin", "platformAdmin", "admin", "system"})
public class ManagedSecretResource {

    private static final Logger LOG = Logger.getLogger(ManagedSecretResource.class);

    private final ManagedSecretRepo repo;
    private final SecretEncryptor encryptor;

    public ManagedSecretResource(ManagedSecretRepo repo, SecretEncryptor encryptor) {
        this.repo = repo;
        this.encryptor = encryptor;
    }

    @GET
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
    public Response getValue(@PathParam("refName") String refName) {
        String realmId = repo.getSecurityContextRealmId();
        ManagedSecret secret = repo.findByRefName(realmId, refName)
                .orElseThrow(() -> new NotFoundException("Secret not found: " + refName));

        if (!secret.isConfigured()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Secret has no value configured"))
                    .build();
        }

        // Legacy plaintext secret (pre-migration): iv is null, keyVersion is 0
        if (secret.getIv() == null || secret.getIv().isEmpty()) {
            return Response.ok(Map.of("value", secret.getValueEncrypted())).build();
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
            } else {
                // No KEKs configured yet — store as plaintext (will be migrated later)
                LOG.warn("KEKs not configured — storing secret as plaintext. "
                        + "Run migration after configuring quantum.secrets.kek.v1");
                secret.setValueEncrypted(request.getValue());
            }
        }
    }
}
