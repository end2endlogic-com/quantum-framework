package com.e2eq.framework.api.system;

import com.e2eq.framework.persistent.MenuHierarchyRepo;
import com.e2eq.framework.persistent.MenuItemRepo;
import com.e2eq.framework.persistent.MenuItemStaticDynamicListRepo;
import com.e2eq.framework.rest.resources.HierarchyResource;
import com.e2eq.framework.test.MenuHierarchyModel;
import com.e2eq.framework.test.MenuItemModel;
import com.e2eq.framework.test.MenuItemStaticDynamicList;
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
