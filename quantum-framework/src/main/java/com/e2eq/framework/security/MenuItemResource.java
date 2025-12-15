package com.e2eq.framework.security;

import com.e2eq.framework.model.general.MenuItemModel;
import com.e2eq.framework.model.persistent.morphia.MenuItemRepo;
import com.e2eq.framework.rest.resources.BaseResource;
import jakarta.ws.rs.Path;



@Path("/system/menuItem")
public class MenuItemResource extends BaseResource<MenuItemModel, MenuItemRepo> {
   protected MenuItemResource (MenuItemRepo repo) {
      super(repo);
   }
}
