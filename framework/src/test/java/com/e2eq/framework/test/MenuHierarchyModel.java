package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.HiearchicalModel;

public class MenuHierarchyModel extends HiearchicalModel<MenuHierarchyModel, MenuItemModel, MenuItemStaticDynamicList> {
    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }
}
