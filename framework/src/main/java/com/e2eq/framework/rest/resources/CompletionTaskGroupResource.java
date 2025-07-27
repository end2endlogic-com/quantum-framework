package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.CompletionTaskGroupRepo;
import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

@Path("/integration/completionTaskGroup")
@RolesAllowed({"user","admin"})
public class CompletionTaskGroupResource extends BaseResource<CompletionTaskGroup, CompletionTaskGroupRepo> {

    public CompletionTaskGroupResource(CompletionTaskGroupRepo repo) {
        super(repo);
    }

    @POST
    @Path("create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public CompletionTaskGroup createGroup(CompletionTaskGroup group) {
        return repo.createGroup(group);
    }

    @GET
    @Path("subscribe/{id}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void subscribe(@PathParam("id") String id, SseEventSink sink, Sse sse) {
        Multi<Object> stream = repo.subscribe(id);
        stream.subscribe().with(item -> {
            if (!sink.isClosed()) {
                sink.send(sse.newEvent(item.toString()));
            }
        },
        failure -> {
            if (!sink.isClosed()) {
                sink.send(sse.newEvent("Error: " + failure.getMessage()));
                sink.close();
            }
        },
        () -> {
            if (!sink.isClosed()) {
                sink.close();
            }
        });
    }
}
