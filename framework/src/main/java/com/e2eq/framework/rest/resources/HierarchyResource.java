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
import org.bson.Document;
import org.bson.types.ObjectId;

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


    @GET
    @Path("/childrenById/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<T> getChildrenById(@PathParam("id") ObjectId id) {
        return hierarchicalRepo.getAllChildren(id);
    }

    @GET
    @Path("/objectsById/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<O> getObjectsById(@PathParam("id") ObjectId id) {
        return hierarchicalRepo.getAllObjectsForHierarchy(id);
    }

    @GET
    @Path("/objectsByRefName/{refName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<O> getObjectsByRefName(@PathParam("refName") String refName) {
        return hierarchicalRepo.getAllObjectsForHierarchy(refName);
    }


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

        // Populate metadata
        Map<String, Object> data = objectMapper.convertValue(object, new TypeReference<>() {
        });
        data.put("id", node.key);
        data.put("skipValidation", false);
        // TODO should pull this from functional domain definition
        data.put("defaultUIActions", List.of("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE"));
        node.data = data;


        // Handle child territories
        if (object.getDescendants() != null && !object.getDescendants().isEmpty()) {
            for ( ObjectId decendentObjectId : object.getDescendants()) {
                // Find the child object first and check if it exists
                T childObject = datastore.find(repo.getPersistentClass())
                        .filter(eq("_id", decendentObjectId))
                        .first();
                if (childObject != null) {
                    // Recursively resolve descendant territories
                    TreeNode childNode = resolveToHierarchy(childObject);
                    node.children.add(childNode);
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
