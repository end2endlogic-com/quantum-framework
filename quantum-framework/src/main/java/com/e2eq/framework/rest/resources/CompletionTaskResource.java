package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.CompletionTaskRepo;
import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

@Path("/integration/completionTask")
@RolesAllowed({"user", "admin"})
public class CompletionTaskResource extends BaseResource<CompletionTask, CompletionTaskRepo> {

    public CompletionTaskResource(CompletionTaskRepo repo) {
        super(repo);
    }

    @POST
    @Path("create/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CompletionTask createTask(@PathParam("groupId") String groupId, CompletionTask task) {
        return repo.createTask(task, groupId);
    }

    @PUT
    @Path("complete/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response completeTask(@PathParam("id") String id,
                                 @QueryParam("status") String status,
                                 @QueryParam("result") String result) {
        Optional<CompletionTask> t = repo.completeTask(id, CompletionTask.Status.valueOf(status), result);
        return t.map(Response::ok).orElseGet(() -> Response.status(Response.Status.NOT_FOUND)).build();
    }
}
