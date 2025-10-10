package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.UserGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class UserGroupRepo extends MorphiaRepo<UserGroup> {
   // given a UserProfileEntityReference find all the userGroups associated with it
   public List<UserGroup> findByUserProfileRef(@Valid @NotNull EntityReference userProfileRef) {
      // query userGroups.userProfiles.entityRefName == userProfileRef.entityRefName
      String query = String.format("userProfiles.entityRefName:%s", userProfileRef.getEntityRefName());
      return getListByQuery(0, -1, query);
   }
}
