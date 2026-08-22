package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.collaboration.Comment;
import com.e2eq.framework.model.persistent.collaboration.CommentChain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.collaboration.CommentChainService;
import com.e2eq.framework.rest.collaboration.CommentOperationException;
import com.e2eq.framework.rest.models.CommentChainCreateRequest;
import com.e2eq.framework.rest.models.CommentCreateRequest;
import com.e2eq.framework.rest.models.CommentApiError;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/comment-chains")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@FunctionalMapping(area = "COLLABORATION", domain = "COMMENT_CHAIN")
@Tag(name = "comment-chains", description = "Hierarchical comments attached to local or external models")
public class CommentChainResource {

    @Inject
    CommentChainService service;

    @POST
    @Operation(operationId = "createCommentChain", summary = "Create a comment chain")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Comment chain created",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentChain.class))),
        @APIResponse(
                responseCode = "default",
                description = "Comment operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentApiError.class)))
    })
    public Response create(@Valid CommentChainCreateRequest request) {
        CommentChain chain = service.createChain(request, actor());
        return Response.status(Response.Status.CREATED).entity(chain).build();
    }

    @GET
    @Path("{chainId}")
    @Operation(operationId = "getCommentChain", summary = "Get a visible comment chain")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Comment chain",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentChain.class))),
        @APIResponse(
                responseCode = "default",
                description = "Comment operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentApiError.class)))
    })
    public CommentChain get(@PathParam("chainId") String chainId) {
        return service.getChain(chainId);
    }

    @GET
    @Path("by-external-subject")
    @Operation(operationId = "listCommentChainsByExternalSubject", summary = "List chains for an external subject")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Matching comment chains",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(type = SchemaType.ARRAY, implementation = CommentChain.class))),
        @APIResponse(
                responseCode = "default",
                description = "Comment operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentApiError.class)))
    })
    public List<CommentChain> byExternalSubject(
            @QueryParam("sourceSystem") String sourceSystem,
            @QueryParam("entityType") String entityType,
            @QueryParam("externalId") String externalId) {
        return service.findExternalSubject(sourceSystem, entityType, externalId);
    }

    @POST
    @Path("{chainId}/comments")
    @Operation(operationId = "createComment", summary = "Add a root comment or reply")
    @APIResponses({
        @APIResponse(
                responseCode = "201",
                description = "Comment created",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = Comment.class))),
        @APIResponse(
                responseCode = "default",
                description = "Comment operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentApiError.class)))
    })
    public Response comment(
            @PathParam("chainId") String chainId,
            @Valid CommentCreateRequest request) {
        Comment comment = service.addComment(chainId, request, actor());
        return Response.status(Response.Status.CREATED).entity(comment).build();
    }

    @GET
    @Path("{chainId}/comments")
    @Operation(operationId = "listComments", summary = "List comments in deterministic chronological order")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Chronological comments",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(type = SchemaType.ARRAY, implementation = Comment.class))),
        @APIResponse(
                responseCode = "default",
                description = "Comment operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentApiError.class)))
    })
    public List<Comment> comments(
            @PathParam("chainId") String chainId,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return service.listComments(chainId, limit);
    }

    @GET
    @Path("{chainId}/comments/{parentCommentId}/replies")
    @Operation(operationId = "listCommentReplies", summary = "List direct replies to a comment")
    @APIResponses({
        @APIResponse(
                responseCode = "200",
                description = "Direct replies",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(type = SchemaType.ARRAY, implementation = Comment.class))),
        @APIResponse(
                responseCode = "default",
                description = "Comment operation failed",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(implementation = CommentApiError.class)))
    })
    public List<Comment> replies(
            @PathParam("chainId") String chainId,
            @PathParam("parentCommentId") String parentCommentId,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return service.listReplies(chainId, parentCommentId, limit);
    }

    private ActorReference actor() {
        PrincipalContext principal = SecurityContext.getPrincipalContext().orElseThrow(() ->
                new CommentOperationException(
                        CommentOperationException.Code.AUTHENTICATED_ACTOR_REQUIRED,
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
