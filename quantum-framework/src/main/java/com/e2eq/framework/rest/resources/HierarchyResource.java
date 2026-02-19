package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.HierarchicalRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.ObjectListRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCursor;
import dev.morphia.MorphiaDatastore;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.types.ObjectId;
import com.e2eq.framework.rest.services.HierarchyTreeService;
import com.e2eq.framework.rest.dto.GenericHierarchyDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// O the base model at each level of the hierarchy
// L the static dynamic list of O's at each level of the hierarchy
// OR the repository for O's at each level of the hierarchy
// LR the repository for static dynamic list of O's at each level of the hierarchy
// T - The HierarchicalModel type
// TR - The repository for HierarchicalModel type

/**
 * Generic resource that exposes REST endpoints for hierarchical structures.
 * It is parameterised with the model type at each level and the associated
 * repositories.
 *
 * @param <O>  The base model at each level of the hierarchy
 * @param <L>  the static dynamic list of {@code O}'s at each level of the hierarchy
 * @param <OR> the repository for {@code O}
 * @param <LR> the repository for the lists of {@code O}
 * @param <T>  the hierarchical model type
 * @param <TR> the repository for the hierarchical model
 */

public class HierarchyResource<
        O extends UnversionedBaseModel,
        L extends StaticDynamicList<O>,
        OR extends MorphiaRepo<O>,
        LR extends ObjectListRepo<O,L,OR>,
        T extends HierarchicalModel<T, O, L>,
        TR extends HierarchicalRepo
       <T, O, L, OR, LR>> extends BaseResource<T,TR>{

    protected HierarchyResource(TR repo) {
        super(repo);
    }

    @Inject
    protected ObjectMapper objectMapper;

    @Inject
    protected TR hierarchicalRepo;

    @Inject
    protected HierarchyTreeService treeService;



    @GET
    @Path("/trees")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<com.e2eq.framework.model.TreeNode> getTrees () {
        return repo.getTrees();
    }

    @GET
    @Path("/{id}/tree")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTree(@PathParam("id") String id,
                            @QueryParam("depth") @DefaultValue("1") int depth) {
        ObjectId oid;
        try {
            oid = new ObjectId(id);
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid id").build();
        }
        depth = Math.max(0, Math.min(depth, 5));
        GenericHierarchyDto dto = treeService.buildTree(oid, repo, depth);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dto).build();
    }

    /**
     * Gets the effective filter string for a hierarchy node.
     * This includes all accumulated dynamic filters AND static ID constraints
     * from the path (root to node). This is the complete filter that would be
     * applied when querying objects for this node.
     *
     * @param id the ObjectId of the hierarchy node
     * @return JSON response containing the effective filter string
     */
    @GET
    @Path("/{id}/effective-filter")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getEffectiveFilter(@PathParam("id") String id) {
        ObjectId oid;
        try {
            oid = new ObjectId(id);
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid id format"))
                    .build();
        }

        try {
            String effectiveFilter = repo.getEffectiveFilterForNode(oid);
            return Response.ok(Map.of(
                    "nodeId", id,
                    "effectiveFilter", effectiveFilter != null ? effectiveFilter : ""
            )).build();
        } catch (NotFoundException ex) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Hierarchy node not found for id: " + id))
                    .build();
        }
    }
}
