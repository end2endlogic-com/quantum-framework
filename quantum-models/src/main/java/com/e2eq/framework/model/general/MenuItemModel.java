package com.e2eq.framework.model.general;

import com.e2eq.framework.model.persistent.base.BaseModel;

public class MenuItemModel extends BaseModel {
    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MENU";
    }
}
