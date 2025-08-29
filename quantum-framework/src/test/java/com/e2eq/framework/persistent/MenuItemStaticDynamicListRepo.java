package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.ObjectListRepo;
import com.e2eq.framework.test.MenuItemModel;
import com.e2eq.framework.test.MenuItemStaticDynamicList;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MenuItemStaticDynamicListRepo extends ObjectListRepo <
        MenuItemModel,
        MenuItemStaticDynamicList,
        MenuItemRepo>  {
}
