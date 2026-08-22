package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.media.MediaAccessGrant;
import com.e2eq.framework.rest.media.MediaReferenceService;
import com.e2eq.framework.rest.media.MediaStorageException;
import com.e2eq.framework.rest.media.MediaUploadResponse;
import com.e2eq.framework.rest.models.MediaDownloadGrantRequest;
import com.e2eq.framework.rest.models.MediaApiError;
import com.e2eq.framework.rest.models.MediaUploadRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/media-references")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@FunctionalMapping(area = "COLLABORATION", domain = "MEDIA_REFERENCE")
@Tag(name = "media-references", description = "Governed media metadata and short-lived access grants")
public class MediaReferenceResource {

    @Inject
    MediaReferenceService service;

    @POST
    @Path("uploads")
    @Operation(operationId = "prepareMediaUpload", summary = "Create media metadata and a direct upload grant")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Media upload prepared",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaUploadResponse.class))),
        @APIResponse(
                responseCode = "default",
                description = "Media operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaApiError.class)))
    })
    public Response prepareUpload(@Valid MediaUploadRequest request) {
        MediaUploadResponse response = service.prepareUpload(request, actor());
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @POST
    @Path("{mediaReferenceId}/upload-completion")
    @Operation(operationId = "completeMediaUpload", summary = "Verify a direct upload and advance governed media status")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Media upload verified",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaReference.class))),
        @APIResponse(
                responseCode = "default",
                description = "Media operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaApiError.class)))
    })
    public MediaReference completeUpload(
            @PathParam("mediaReferenceId") String mediaReferenceId) {
        return service.completeUpload(mediaReferenceId);
    }

    @GET
    @Path("{mediaReferenceId}")
    @Operation(operationId = "getMediaReference", summary = "Get visible media metadata")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Media metadata",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaReference.class))),
        @APIResponse(
                responseCode = "default",
                description = "Media operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaApiError.class)))
    })
    public MediaReference get(@PathParam("mediaReferenceId") String mediaReferenceId) {
        return service.get(mediaReferenceId);
    }

    @POST
    @Path("{mediaReferenceId}/download-grants")
    @Operation(operationId = "prepareMediaDownload", summary = "Create a short-lived media download grant")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Short-lived download grant",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaAccessGrant.class))),
        @APIResponse(
                responseCode = "default",
                description = "Media operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = MediaApiError.class)))
    })
    public MediaAccessGrant prepareDownload(
            @PathParam("mediaReferenceId") String mediaReferenceId,
            @Valid MediaDownloadGrantRequest request) {
        return service.prepareDownload(
                mediaReferenceId,
                request == null ? null : request.contentDisposition);
    }

    private ActorReference actor() {
        PrincipalContext principal = SecurityContext.getPrincipalContext().orElseThrow(() ->
                new MediaStorageException(
                        MediaStorageException.Code.ACCESS_DENIED,
                        "An authenticated Quantum principal is required"));
        ActorReference.ActorType actorType = Arrays.asList(principal.getRoles()).contains("system")
                ? ActorReference.ActorType.SERVICE
                : ActorReference.ActorType.USER;
        return ActorReference.builder()
                .actorId(principal.getUserId())
                .actorType(actorType)
                .displayName(principal.getUserId())
                .realm(principal.getDefaultRealm())
                .organizationRefName(principal.getDataDomain().getOrgRefName())
                .build();
    }
}
