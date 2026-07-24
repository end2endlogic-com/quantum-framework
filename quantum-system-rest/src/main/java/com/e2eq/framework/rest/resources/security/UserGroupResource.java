package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.rest.core.BaseResource;
import jakarta.ws.rs.Path;

@Path("/user/usergroup")
public class UserGroupResource extends BaseResource<UserGroup, UserGroupRepo> {
   protected UserGroupResource (UserGroupRepo repo) {
      super(repo);
   }
}
