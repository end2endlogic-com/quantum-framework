package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.StaticDynamicList;

public class MenuItemStaticDynamicList extends StaticDynamicList<MenuItemModel> {



    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }
}
