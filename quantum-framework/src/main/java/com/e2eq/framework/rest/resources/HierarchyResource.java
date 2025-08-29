package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.HierarchicalRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.ObjectListRepo;
import com.fasterxml.jackson.core.type.TypeReference;
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

import static dev.morphia.query.filters.Filters.eq;

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
    protected MorphiaDatastore datastore;

    @Inject
    protected TR hierarchicalRepo;

    @Inject
    protected HierarchyTreeService treeService;



    @GET
    @Path("/trees")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<TreeNode> getTrees () {
        List<TreeNode> nodes = new ArrayList<>();
        Document query = new Document("parent", new Document("$exists", false));
        try (MongoCursor<T> cursor = datastore.getCollection(repo.getPersistentClass()).find(query).iterator()) {
            while (cursor.hasNext()) {
                T root = cursor.next();
                nodes.add(resolveToHierarchy(root));
            }
        }

        return nodes;
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
        GenericHierarchyDto dto = treeService.buildTree(oid, repo.getPersistentClass(), depth);
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(dto).build();
    }

    private TreeNode toTreeNode(T object) {
        TreeNode node = new TreeNode();
        node.key = object.getId().toHexString();
        node.label = object.getDisplayName();
        node.icon = "pi pi-map-marker";
        return node;
    }

    private TreeNode resolveToHierarchy(T object) {
        // Convert object to TreeNode
        TreeNode node = toTreeNode(object);

        // Populate curated metadata only (avoid leaking internal fields)
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("id", node.key);
        data.put("displayName", object.getDisplayName());
        data.put("skipValidation", false);
        // TODO should pull this from functional domain definition
        data.put("defaultUIActions", List.of("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE"));
        node.data = data;

        // Guard against accidental cycles
        return resolveChildren(object, node, new java.util.HashSet<>());
    }

    private TreeNode resolveChildren(T object, TreeNode node, java.util.Set<ObjectId> visited) {
        if (object.getId() != null && !visited.add(object.getId())) {
            // already visited, break the cycle
            return node;
        }

        // Handle child nodes with one batch fetch per level
        if (object.getDescendants() != null && !object.getDescendants().isEmpty()) {
            List<ObjectId> ids = object.getDescendants();
            List<T> children = datastore.find(repo.getPersistentClass())
                    .filter(dev.morphia.query.filters.Filters.in("_id", ids))
                    .iterator().toList();
            java.util.Map<ObjectId, T> byId = new java.util.HashMap<>();
            for (T c : children) {
                if (c.getId() != null) {
                    byId.put(c.getId(), c);
                }
            }
            for (ObjectId id : ids) {
                T childObject = byId.get(id);
                if (childObject != null) {
                    TreeNode childNode = toTreeNode(childObject);
                    // Curated child data
                    java.util.Map<String, Object> childData = new java.util.HashMap<>();
                    childData.put("id", childNode.key);
                    childData.put("displayName", childObject.getDisplayName());
                    childData.put("skipValidation", false);
                    childData.put("defaultUIActions", List.of("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE"));
                    childNode.data = childData;

                    node.children.add(resolveChildren(childObject, childNode, visited));
                }
            }
        }
        return node;
    }

    public static class TreeNode {
        public String key;
        public String label;
        public Map<String, Object> data;
        public String icon;
        public List<TreeNode> children = new ArrayList<>();
    }

}
