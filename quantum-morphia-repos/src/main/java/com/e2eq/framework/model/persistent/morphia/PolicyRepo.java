package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Policy;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PolicyRepo extends MorphiaRepo<Policy> {
   /**
    * Returns all policies in the given realm bypassing permission filters and SecurityIdentity.
    * Intended for internal hydration of RuleContext.
    */
   public java.util.List<Policy> getAllListIgnoreRules(String realmId) {
      dev.morphia.Datastore ds = morphiaDataStore.getDataStore(realmId);
      dev.morphia.query.MorphiaCursor<Policy> cursor = ds.find(Policy.class).iterator();
      try (cursor) {
         return cursor.toList();
      }
   }
}
