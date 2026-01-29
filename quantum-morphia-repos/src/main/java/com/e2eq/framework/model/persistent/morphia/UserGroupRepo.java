package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.security.UserGroup;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
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
      String query = String.format("userProfiles.entityRefName:\"%s\"", userProfileRef.getEntityRefName());
      List<UserGroup> userGroups =  getListByQuery(0, -1, query);
      if (userGroups.isEmpty()) {
         Log.warnf("No userGroups found for userProfileRef:\"%s\"", userProfileRef.getEntityRefName());
      }
      return userGroups;
   }

   /**
    * Find all UserGroups associated with a UserProfile in a specific realm, bypassing security rules.
    * This is needed during role resolution when the PrincipalContext is being built and the
    * SecurityContext is not yet available (e.g., during login when the user isn't authenticated yet).
    *
    * This method queries the database directly without going through getListByQuery() to avoid
    * the security filter checks that require a PrincipalContext.
    *
    * @param realm the realm/database to query
    * @param userProfileRef the UserProfile entity reference
    * @return list of UserGroups containing this UserProfile
    */
   public List<UserGroup> findByUserProfileRefWithIgnoreRules(@NotNull String realm, @Valid @NotNull EntityReference userProfileRef) {
      Log.debugf("UserGroupRepo.findByUserProfileRefWithIgnoreRules: querying realm=%s for userProfileRef=%s (bypassing security rules)",
          realm, userProfileRef.getEntityRefName());

      Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
      Query<UserGroup> query = ds.find(getPersistentClass())
          .filter(Filters.eq("userProfiles.entityRefName", userProfileRef.getEntityRefName()));

      List<UserGroup> userGroups = query.iterator().toList();

      if (userGroups.isEmpty()) {
         Log.warnf("No userGroups found for userProfileRef:\"%s\" in realm:%s (ignoreRules=true)", userProfileRef.getEntityRefName(), realm);
      } else {
         Log.debugf("Found %d userGroups for userProfileRef:\"%s\" in realm:%s (ignoreRules=true)", userGroups.size(), userProfileRef.getEntityRefName(), realm);
      }
      return userGroups;
   }

   /**
    * @deprecated Use {@link #findByUserProfileRefWithIgnoreRules(String, EntityReference)} instead.
    * This method calls getListByQuery which requires a PrincipalContext to be set.
    */
   @Deprecated
   public List<UserGroup> findByUserProfileRef(@NotNull String realm, @Valid @NotNull EntityReference userProfileRef) {
      String query = String.format("userProfiles.entityRefName:\"%s\"", userProfileRef.getEntityRefName());
      Log.debugf("UserGroupRepo.findByUserProfileRef: querying realm=%s for userProfileRef=%s", realm, userProfileRef.getEntityRefName());
      List<UserGroup> userGroups = getListByQuery(realm, 0, -1, query, null, null);
      if (userGroups.isEmpty()) {
         Log.warnf("No userGroups found for userProfileRef:\"%s\" in realm:%s", userProfileRef.getEntityRefName(), realm);
      } else {
         Log.debugf("Found %d userGroups for userProfileRef:\"%s\" in realm:%s", userGroups.size(), userProfileRef.getEntityRefName(), realm);
      }
      return userGroups;
   }
}
