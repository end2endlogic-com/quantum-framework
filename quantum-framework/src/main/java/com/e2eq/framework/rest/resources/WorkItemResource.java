package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.morphia.CompletionTaskRepo;
import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import com.e2eq.framework.model.persistent.tasks.WorkItemOperationException;
import com.e2eq.framework.model.persistent.tasks.WorkerType;
import com.e2eq.framework.rest.models.WorkItemActionRequest;
import io.quarkus.security.identity.SecurityIdentity;
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
import org.eclipse.microprofile.openapi.annotations.Operation;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Governed queue API shared by human, agent, and service workers.
 */
@Path("/work-items")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@FunctionalMapping(area = "TASK", domain = "COMPLETION_TASK")
public class WorkItemResource {
    private static final long DEFAULT_AGENT_LEASE_SECONDS = 300;

    @Inject
    CompletionTaskRepo repo;

    @Inject
    SecurityIdentity identity;

    @POST
    @RolesAllowed({"admin", "system"})
    @Operation(operationId = "createWorkItem", summary = "Create a governed work item")
    public CompletionTask create(@QueryParam("groupId") String groupId, CompletionTask workItem) {
        return repo.createWorkItem(workItem, groupId, actorRef());
    }

    @GET
    @Path("eligible")
    @Operation(operationId = "listEligibleWorkItems", summary = "List work items the current actor may claim")
    public List<CompletionTask> eligible(@QueryParam("workerType") @DefaultValue("HUMAN") WorkerType workerType,
                                         @QueryParam("queueRef") String queueRef,
                                         @QueryParam("limit") @DefaultValue("50") int limit) {
        WorkerType authorizedWorkerType = authorizeWorkerType(workerType);
        return repo.listEligible(authorizedWorkerType, identity.getRoles(),
                identityValues("profileRefs"), identityValues("capabilityRefs"), queueRef, limit);
    }

    @GET
    @Path("mine")
    @Operation(operationId = "listMyWorkItems", summary = "List work items currently claimed by the actor")
    public List<CompletionTask> mine(@QueryParam("limit") @DefaultValue("50") int limit) {
        return repo.listClaimedBy(actorRef(), limit);
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getWorkItem", summary = "Get a visible work item for a queue deep link")
    public CompletionTask get(@PathParam("id") String id,
                              @QueryParam("workerType") @DefaultValue("HUMAN") WorkerType workerType) {
        WorkerType authorizedWorkerType = authorizeWorkerType(workerType);
        return repo.getVisible(id, actorRef(), authorizedWorkerType, identity.getRoles(),
                identityValues("profileRefs"), identityValues("capabilityRefs"));
    }

    @POST
    @Path("{id}/claim")
    @Operation(operationId = "claimWorkItem", summary = "Atomically claim an eligible work item")
    public CompletionTask claim(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        WorkerType workerType = authorizeWorkerType(
                request.workerType == null ? WorkerType.HUMAN : request.workerType);
        return repo.claim(id, request.expectedVersion, actorRef(), workerType, identity.getRoles(),
                identityValues("profileRefs"), identityValues("capabilityRefs"), lease(workerType, request));
    }

    @POST
    @Path("{id}/start")
    @Operation(operationId = "startWorkItem", summary = "Move a claimed work item into progress")
    public CompletionTask start(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        return repo.start(id, request.expectedVersion, actorRef());
    }

    @POST
    @Path("{id}/complete")
    @Operation(operationId = "completeWorkItem", summary = "Complete a work item and make its result available to orchestration")
    public CompletionTask complete(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        return repo.complete(id, request.expectedVersion, actorRef(), request.expectedAssessmentVersion,
                request.result, request.resultPayload);
    }

    @POST
    @Path("{id}/fail")
    @Operation(operationId = "failWorkItem", summary = "Fail a work item with an explicit result or reason")
    public CompletionTask fail(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        return repo.fail(id, request.expectedVersion, actorRef(), request.result, request.resultPayload);
    }

    @POST
    @Path("{id}/release")
    @Operation(operationId = "releaseWorkItem", summary = "Release a claimed work item back to its queue")
    public CompletionTask release(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        return repo.release(id, request.expectedVersion, actorRef());
    }

    @POST
    @Path("{id}/heartbeat")
    @Operation(operationId = "heartbeatWorkItem", summary = "Extend the lease for an active work item")
    public CompletionTask heartbeat(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        long leaseSeconds = request.leaseSeconds == null ? DEFAULT_AGENT_LEASE_SECONDS : request.leaseSeconds;
        if (leaseSeconds < 30 || leaseSeconds > 3600) {
            throw invalid("leaseSeconds must be between 30 and 3600");
        }
        return repo.heartbeat(id, request.expectedVersion, actorRef(), Duration.ofSeconds(leaseSeconds));
    }

    @POST
    @Path("{id}/cancel")
    @RolesAllowed({"admin", "system"})
    @Operation(operationId = "cancelWorkItem", summary = "Cancel a non-terminal work item")
    public CompletionTask cancel(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        return repo.cancel(id, request.expectedVersion);
    }

    @POST
    @Path("{id}/expire")
    @RolesAllowed("system")
    @Operation(operationId = "expireWorkItem", summary = "Expire a non-terminal work item")
    public CompletionTask expire(@PathParam("id") String id, @Valid WorkItemActionRequest request) {
        requireRequest(request);
        return repo.expire(id, request.expectedVersion);
    }

    private Duration lease(WorkerType workerType, WorkItemActionRequest request) {
        if (workerType == WorkerType.HUMAN && request.leaseSeconds == null) {
            return null;
        }
        long leaseSeconds = request.leaseSeconds == null ? DEFAULT_AGENT_LEASE_SECONDS : request.leaseSeconds;
        if (leaseSeconds < 30 || leaseSeconds > 3600) {
            throw invalid("leaseSeconds must be between 30 and 3600");
        }
        return Duration.ofSeconds(leaseSeconds);
    }

    private WorkerType authorizeWorkerType(WorkerType requested) {
        if (requested == WorkerType.HUMAN) {
            return requested;
        }
        if (!identity.hasRole("system") && !identity.hasRole("admin")) {
            throw new WorkItemOperationException(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "Only an agent/service identity may use the " + requested + " queue");
        }
        return requested;
    }

    private void requireRequest(WorkItemActionRequest request) {
        if (request == null || request.expectedVersion == null) {
            throw invalid("expectedVersion is required");
        }
    }

    private WorkItemOperationException invalid(String message) {
        return new WorkItemOperationException(WorkItemOperationException.Code.INVALID_REQUEST, message);
    }

    private String actorRef() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            throw new WorkItemOperationException(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "An authenticated actor is required");
        }
        return identity.getPrincipal().getName();
    }

    private Set<String> identityValues(String attributeName) {
        Object value = identity.getAttribute(attributeName);
        if (value == null) {
            return Set.of();
        }
        if (value instanceof Collection<?> values) {
            Set<String> result = new LinkedHashSet<>();
            values.stream().map(String::valueOf).forEach(result::add);
            return result;
        }
        return Set.of(String.valueOf(value));
    }
}
