package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.security.UserGroup;
import jakarta.ws.rs.Path;

@Path("/user/usergroup")
public class UserGroupResource extends BaseResource<UserGroup, UserGroupRepo> {
   protected UserGroupResource (UserGroupRepo repo) {
      super(repo);
   }
}
