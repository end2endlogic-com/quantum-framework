package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.general.MenuItemStaticDynamicList;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class MenuHierarchyRepo extends HierarchicalRepo<
        MenuHierarchyModel,
        MenuItemModel,
        MenuItemStaticDynamicList,
        MenuItemRepo,
        MenuItemStaticDynamicListRepo
        > {

   // filtered menu, given a root menu item, a list of identities filter the resulting hiearchy
   public MenuHierarchyModel getFilteredMenu(String rootId, List<String> identities) {
      Objects.requireNonNull(rootId, "rootId must not be null");

      MenuHierarchyModel root = findById(rootId).orElse(null);
      if (root == null) return null;

      // now we want to travers the root hierarchy perhaps using a HierarchyVisitor to
      // check if the menu item has a matching allowed identity if it does keep it if not don't include it
      // and its descendants
      return filterHierarchy(root, identities);
   }

   private MenuHierarchyModel filterHierarchy(MenuHierarchyModel node, List<String> identities) {
      if (node == null) {
         return null;
      }

      MenuItemModel menuItem = getMenuItemFromHierarchy(node);
      if (menuItem != null && !menuItem.getAllowedIdentities().isEmpty() && Collections.disjoint(menuItem.getAllowedIdentities(), identities)) {
         // If the current node's item has allowedIdentities but none match, exclude it and its subtree.
         return null;
      }

      // The current node is a match, so we create a clone.
      MenuHierarchyModel filteredNode = new MenuHierarchyModel();
      filteredNode.setId(node.getId());
      filteredNode.setDisplayName(node.getDisplayName());
      filteredNode.setStaticDynamicList(node.getStaticDynamicList());
      filteredNode.setParent(node.getParent());

      // Now, process its children.
      if (node.getDescendants() != null && !node.getDescendants().isEmpty()) {
         List<ObjectId> childIds = node.getDescendants();
         List<MenuHierarchyModel> children = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).find(MenuHierarchyModel.class)
                                                .filter(dev.morphia.query.filters.Filters.in("_id", childIds))
                                                .iterator().toList();

         List<ObjectId> filteredDescendantIds = new ArrayList<>();
         for (MenuHierarchyModel child : children) {
            MenuHierarchyModel filteredChild = filterHierarchy(child, identities);
            if (filteredChild != null) {
               // Only add children that are not filtered out.
               filteredDescendantIds.add(filteredChild.getId());
            }
         }
         filteredNode.setDescendants(filteredDescendantIds);
      }

      return filteredNode;
   }

   private MenuItemModel getMenuItemFromHierarchy(MenuHierarchyModel hierarchyNode) {
      if (hierarchyNode != null && hierarchyNode.getStaticDynamicList() != null &&
             !hierarchyNode.getStaticDynamicList().getItems().isEmpty()) {
         return hierarchyNode.getStaticDynamicList().getItems().get(0);
      }
      return null;
   }
}
