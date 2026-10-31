package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.model.BootstrapPackRunStatus;
import com.e2eq.framework.bootstrap.model.InitializeTenantApplicationRequest;
import com.e2eq.framework.bootstrap.runtime.TenantApplicationInitializationService;
import com.e2eq.framework.bootstrap.runtime.TenantInitializationFailure;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import java.util.Optional;
import java.util.Set;

@Path("/internal/tenant-initializations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("tenant-initializer")
@FunctionalMapping(area="SYSTEM", domain="TENANT_INITIALIZATION")
@ApplicationScoped
@IfBuildProperty(name="quantum.tenant-initialization.enabled", stringValue="true")
public class TenantApplicationInitializationResource {
    @Inject TenantApplicationInitializationService initialization;
    @Inject JsonWebToken jwt;
    @ConfigProperty(name="quantum.tenant-initialization.application-id") Optional<String> applicationId;
    @ConfigProperty(name="quantum.tenant-initialization.caller-application-id") Optional<String> callerApplicationId;

    @POST
    @FunctionalAction(value="CREATE", bypassDataScoping=true)
    @Operation(operationId="initializeTenantApplication", summary="Apply a pinned application bootstrap to a centrally registered realm")
    @APIResponse(responseCode="200", description="Terminal receipt; inspect status and step outcomes", content=@Content(schema=@Schema(implementation=BootstrapPackRun.class)))
    @APIResponse(responseCode="202", description="Execution is already running; no effects repeated", content=@Content(schema=@Schema(implementation=BootstrapPackRun.class)))
    @APIResponse(responseCode="400", description="Invalid request", content=@Content(schema=@Schema(implementation=Problem.class)))
    @APIResponse(responseCode="403", description="Service identity rejected", content=@Content(schema=@Schema(implementation=Problem.class)))
    @APIResponse(responseCode="409", description="Pinned binding or execution key conflict", content=@Content(schema=@Schema(implementation=Problem.class)))
    @APIResponse(responseCode="422", description="Realm or pack is not admitted for this request", content=@Content(schema=@Schema(implementation=Problem.class)))
    @APIResponse(responseCode="503", description="Runtime binding is not configured", content=@Content(schema=@Schema(implementation=Problem.class)))
    public Response initialize(@org.eclipse.microprofile.openapi.annotations.parameters.RequestBody(required=true) InitializeTenantApplicationRequest request,
            @HeaderParam("X-Realm") @org.eclipse.microprofile.openapi.annotations.parameters.Parameter(required=true,
                description="Must match the centrally registered realmRef in the request") String realmHeader) {
        try {
            authorizeService();
            if (request == null || realmHeader == null || !realmHeader.equals(request.realmRef()))
                throw new TenantInitializationFailure(400, "INITIALIZATION_REALM_HEADER_MISMATCH", "X-Realm must match realmRef");
            BootstrapPackRun run = initialization.initialize(request, "service:"+callerApplicationId.orElseThrow());
            return Response.status(run.status() == BootstrapPackRunStatus.RUNNING ? 202 : 200).entity(run).build();
        } catch (TenantInitializationFailure e) {
            return Response.status(e.status()).entity(new Problem(e.code(), e.getMessage())).build();
        } catch (ForbiddenException e) {
            return Response.status(403).entity(new Problem("INITIALIZATION_PERMISSION_DENIED",
                "The central policy must authorize this service to initialize the selected realm.")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(422).entity(new Problem("INITIALIZATION_PLAN_INVALID", e.getMessage())).build();
        } catch (RuntimeException e) {
            io.quarkus.logging.Log.error("Application initialization could not produce a receipt", e);
            return Response.status(503).entity(new Problem("INITIALIZATION_UNAVAILABLE",
                "No receipt could be returned. Inspect server diagnostics; reuse the same execution key to observe any persisted run.")).build();
        }
    }

    @POST @Path("/{executionRef}/recover")
    @FunctionalAction(value="RECOVER",bypassDataScoping=true)
    @Operation(operationId="recoverTenantApplication",summary="Reconcile committed effects at an exact checkpoint and resume unstarted steps")
    @APIResponse(responseCode="200",description="Recovery receipt",content=@Content(schema=@Schema(implementation=BootstrapPackRun.class)))
    @APIResponse(responseCode="409",description="Stale checkpoint, runtime drift, unsupported or uncertain effects",content=@Content(schema=@Schema(implementation=Problem.class)))
    public Response recover(@PathParam("executionRef") String executionRef,
            @HeaderParam("X-Realm") @org.eclipse.microprofile.openapi.annotations.parameters.Parameter(required=true) String realm,
            @org.eclipse.microprofile.openapi.annotations.parameters.RequestBody(required=true) com.e2eq.framework.bootstrap.model.RecoverTenantInitializationRequest request) {
        try {
            authorizeService();
            var run=initialization.findRun(executionRef);
            if (run.isEmpty() || realm==null || !realm.equals(run.get().scope().get("realmRef")))
                throw new TenantInitializationFailure(422,"INITIALIZATION_REALM_HEADER_MISMATCH","X-Realm must match the stored run");
            return Response.ok(initialization.recover(executionRef,request,jwt.getSubject())).build();
        } catch(TenantInitializationFailure e) { return Response.status(e.status()).entity(new Problem(e.code(),e.getMessage())).build(); }
    }

    void authorizeService() {
        String app = applicationId.filter(v -> !v.isBlank()).orElseThrow(() ->
            new TenantInitializationFailure(503, "INITIALIZATION_RUNTIME_UNCONFIGURED", "application-id is required"));
        String caller = callerApplicationId.filter(v -> !v.isBlank()).orElseThrow(() ->
            new TenantInitializationFailure(503, "INITIALIZATION_RUNTIME_UNCONFIGURED", "caller-application-id is required"));
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
            || !jwt.getGroups().contains("tenant-initializer")
            || !DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE.equals(jwt.getClaim(DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM))
            || (jwt.getAudience() != null && !jwt.getAudience().isEmpty()) || !caller.equals(jwt.getClaim("azp")))
            throw new TenantInitializationFailure(403, "INITIALIZATION_SERVICE_IDENTITY_REQUIRED",
                "An issuer-validated service passport and authorized caller are required");
    }
    public record Problem(String code, String detail) {}
}
