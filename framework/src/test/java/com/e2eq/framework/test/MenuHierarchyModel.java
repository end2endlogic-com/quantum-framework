package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;

public class MenuHierarchyModel extends HierarchicalModel<MenuHierarchyModel, MenuItemModel, MenuItemStaticDynamicList> {
    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }
}
