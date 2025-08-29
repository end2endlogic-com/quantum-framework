package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.HierarchicalRepo;
import com.e2eq.framework.test.MenuHierarchyModel;
import com.e2eq.framework.test.MenuItemModel;
import com.e2eq.framework.test.MenuItemStaticDynamicList;
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
