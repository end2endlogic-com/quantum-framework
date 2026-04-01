package com.e2eq.framework.secrets.rest;

import com.e2eq.framework.secrets.model.ManagedSecret;
import com.e2eq.framework.secrets.model.ManagedSecretRepo;
import com.e2eq.framework.secrets.rest.dto.ManagedSecretCreateUpdateRequest;
import com.e2eq.framework.secrets.rest.dto.ManagedSecretResponse;
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

import java.util.List;

@Path("/settings/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"tenantAdmin", "platformAdmin", "admin", "system"})
public class ManagedSecretResource {

    private final ManagedSecretRepo repo;

    public ManagedSecretResource(ManagedSecretRepo repo) {
        this.repo = repo;
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

    private static ManagedSecretResponse toResponse(ManagedSecret secret) {
        return ManagedSecretResponse.builder()
                .refName(secret.getRefName())
                .secretType(secret.getSecretType())
                .displayName(secret.getDisplayName())
                .description(secret.getDescription())
                .providerType(secret.getProviderType())
                .realmDefault(secret.isRealmDefault())
                .configured(secret.isConfigured())
                .build();
    }

    private static void apply(ManagedSecret secret, ManagedSecretCreateUpdateRequest request, boolean create) {
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
            secret.setValueEncrypted(request.getValue());
        }
    }
}
