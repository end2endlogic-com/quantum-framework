package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.model.persistent.morphia.MenuItemRepo;
import com.e2eq.framework.model.persistent.morphia.MenuItemStaticDynamicListRepo;
import com.e2eq.framework.rest.resources.HierarchyResource;
import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.general.MenuItemStaticDynamicList;
import jakarta.ws.rs.Path;

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
}
