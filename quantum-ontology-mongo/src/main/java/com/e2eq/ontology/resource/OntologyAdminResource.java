package com.e2eq.ontology.resource;

import com.e2eq.ontology.model.OntologyMeta;
import com.e2eq.ontology.repo.OntologyMetaRepo;
import com.e2eq.ontology.service.OntologyReindexer;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.Map;
import java.util.concurrent.Executor;

@Path("/ontology/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class OntologyAdminResource {

    @Inject
    OntologyMetaRepo metaRepo;

    @Inject
    OntologyReindexer reindexer;

    @Inject
    Executor managedExecutor;

    @GET
    @Path("/meta")
    public OntologyMeta meta() {
        return metaRepo.getSingleton().orElse(null);
    }

    /**
     * Trigger full ontology reindex with Server-Sent Events for real-time progress streaming.
     * This is the preferred method for reindexing as it provides real-time feedback.
     *
     * @param realm the realm to reindex
     * @param force if true, purge derived edges before reindexing
     * @param eventSink SSE event sink for streaming progress
     * @param sse SSE factory
     */
    @POST
    @Path("/reindex/stream")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void triggerReindexStream(@QueryParam("realm") @DefaultValue("default") String realm,
                                     @QueryParam("force") @DefaultValue("false") boolean force,
                                     @Context SseEventSink eventSink,
                                     @Context Sse sse) {
        Multi.createFrom().emitter(emitter -> {
            try {
                reindexer.runAsync(realm, force, emitter);
            } catch (Throwable t) {
                emitter.fail(t);
            }
        }).emitOn(managedExecutor)
          .subscribe().with(
              message -> {
                  if (!eventSink.isClosed()) {
                      eventSink.send(sse.newEvent((String) message));
                  }
              },
              failure -> {
                  if (!eventSink.isClosed()) {
                      eventSink.send(sse.newEvent("Error: " + failure.getMessage()));
                      eventSink.close();
                  }
              },
              () -> {
                  if (!eventSink.isClosed()) {
                      eventSink.send(sse.newEvent("Reindex task completed"));
                      eventSink.close();
                  }
              }
          );
    }

    /**
     * Trigger full ontology reindex (legacy endpoint - returns immediately).
     * For real-time progress, use POST /reindex/stream instead.
     *
     * @param realm the realm to reindex
     * @return accepted response with current status
     */
    @POST
    @Path("/reindex")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response triggerReindex(@QueryParam("realm") @DefaultValue("default") String realm) {
        reindexer.runAsync(realm);
        return Response.accepted(Map.of(
                "message", "Reindex started. Use GET /ontology/admin/reindex/status to poll for progress, or use POST /ontology/admin/reindex/stream for real-time SSE updates.",
                "status", reindexer.status(),
                "realm", realm
        )).build();
    }

    @GET
    @Path("/reindex/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", reindexer.isRunning(),
                "status", reindexer.status(),
                "result", reindexer.getResult()
        );
    }
}
