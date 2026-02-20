package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.model.persistent.morphia.MenuItemRepo;
import com.e2eq.framework.model.persistent.morphia.MenuItemStaticDynamicListRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.rest.resources.HierarchyResource;
import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.general.MenuItemStaticDynamicList;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Path("/system/menuItemHierarchy")
public class MenuItemHierarchyResource extends HierarchyResource<
        MenuItemModel,
        MenuItemStaticDynamicList,
        MenuItemRepo,
        MenuItemStaticDynamicListRepo,
        MenuHierarchyModel,
        MenuHierarchyRepo> {

    protected MenuItemHierarchyResource(MenuHierarchyRepo repo) {
        super(repo);
    }

   @GET
   @Path("/trees/filtered")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getFilteredTree(@QueryParam("menuRootRefName") String menuRootRefName,@QueryParam("identities") List<String> identities) {


      Optional<MenuHierarchyModel> rootMenu = repo.findByRefName("root-menu");
      if (rootMenu.isEmpty()) {
         return Response.status(Response.Status.NOT_FOUND).entity("Root menu 'root-menu' not found.").build();
      }

      MenuHierarchyModel filteredMenu = repo.getFilteredMenu(
         rootMenu.get().getId().toString(),
         identities
      );

      if (filteredMenu == null) {
         // This can happen if the root itself is filtered out.
         // Return an empty structure or 404, depending on desired client behavior.
         // Returning 404 might be better to indicate no accessible menu.
         return Response.status(Response.Status.NOT_FOUND).entity("No accessible menu items found for the user.").build();
      }

      // The repo returns a model; we should convert it to a DTO for the response.
      // Assuming a treeService similar to what's in HierarchyResource for building DTOs.
      return Response.ok(treeService.buildTree(filteredMenu.getId(), repo, 100)).build();
   }
}
