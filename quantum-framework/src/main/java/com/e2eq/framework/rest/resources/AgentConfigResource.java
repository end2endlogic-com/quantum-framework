package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.agent.Agent;
import com.e2eq.framework.model.persistent.morphia.AgentRepo;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;

/**
 * CRUD REST resource for Agent configuration (LLM + prompt context + enabled tools).
 * Agents are realm-scoped; pass {@code realm} as a query parameter on every request.
 *
 * @see Agent
 * @see AgentRepo
 */
@Path("/api/agent/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@FunctionalMapping(area = "integration", domain = "agentConfig")
public class AgentConfigResource {

    @Inject
    AgentRepo agentRepo;

    /**
     * Lists all agents in the given realm.
     *
     * @param realm tenant realm (required)
     * @return list of agents ordered by refName
     */
    @GET
    @Path("/list")
    @FunctionalAction("list")
    public Response list(@QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"realm query parameter is required\"}")
                    .build();
        }
        List<Agent> agents = agentRepo.list(realm);
        return Response.ok(agents).build();
    }

    /**
     * Retrieves an agent by its refName within the realm.
     *
     * @param refName agent reference name
     * @param realm   tenant realm (required)
     * @return the agent, or 404 if not found
     */
    @GET
    @Path("/{refName}")
    @FunctionalAction("view")
    public Response getByRefName(@PathParam("refName") String refName,
                                 @QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"realm query parameter is required\"}")
                    .build();
        }
        Optional<Agent> agent = agentRepo.findByRefName(realm, refName);
        return agent.map(a -> Response.ok(a).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"agent not found: " + refName + "\"}")
                        .build());
    }

    /**
     * Retrieves an agent by its ObjectId within the realm.
     *
     * @param id    agent ObjectId (hex string)
     * @param realm tenant realm (required)
     * @return the agent, or 404 if not found
     */
    @GET
    @Path("/id/{id}")
    @FunctionalAction("view")
    public Response getById(@PathParam("id") String id,
                            @QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"realm query parameter is required\"}")
                    .build();
        }
        Optional<Agent> agent = agentRepo.findById(realm, id);
        return agent.map(a -> Response.ok(a).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"agent not found: " + id + "\"}")
                        .build());
    }

    /**
     * Saves (creates or updates) an agent in the given realm.
     * If the agent has an id, it updates the existing record; otherwise it creates a new one.
     *
     * @param agent the agent to save
     * @param realm tenant realm (required)
     * @return the saved agent with its id
     */
    @POST
    @FunctionalAction("save")
    public Response save(Agent agent, @QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"realm query parameter is required\"}")
                    .build();
        }
        Agent saved = agentRepo.save(realm, agent);
        return Response.ok(saved).build();
    }

    /**
     * Deletes an agent by its refName within the realm.
     *
     * @param refName agent reference name
     * @param realm   tenant realm (required)
     * @return 200 if deleted, 404 if not found
     */
    @DELETE
    @Path("/{refName}")
    @FunctionalAction("delete")
    public Response deleteByRefName(@PathParam("refName") String refName,
                                    @QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"realm query parameter is required\"}")
                    .build();
        }
        Optional<Agent> agent = agentRepo.findByRefName(realm, refName);
        if (agent.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"agent not found: " + refName + "\"}")
                    .build();
        }
        boolean deleted = agentRepo.deleteById(realm, agent.get().getId().toHexString());
        if (deleted) {
            return Response.ok("{\"deleted\":true,\"refName\":\"" + refName + "\"}").build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\":\"delete failed\"}")
                .build();
    }

    /**
     * Deletes an agent by its ObjectId within the realm.
     *
     * @param id    agent ObjectId (hex string)
     * @param realm tenant realm (required)
     * @return 200 if deleted, 404 if not found
     */
    @DELETE
    @Path("/id/{id}")
    @FunctionalAction("delete")
    public Response deleteById(@PathParam("id") String id,
                               @QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"realm query parameter is required\"}")
                    .build();
        }
        boolean deleted = agentRepo.deleteById(realm, id);
        if (deleted) {
            return Response.ok("{\"deleted\":true,\"id\":\"" + id + "\"}").build();
        }
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"agent not found: " + id + "\"}")
                .build();
    }
}
