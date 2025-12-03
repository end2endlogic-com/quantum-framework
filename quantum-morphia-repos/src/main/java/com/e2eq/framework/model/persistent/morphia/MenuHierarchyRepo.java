package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.general.MenuItemStaticDynamicList;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MenuHierarchyRepo extends HierarchicalRepo<
        MenuHierarchyModel,
        MenuItemModel,
        MenuItemStaticDynamicList,
        MenuItemRepo,
        MenuItemStaticDynamicListRepo
        > {
}
