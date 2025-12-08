package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PolicyRepo extends MorphiaRepo<Policy> {
   /**
    * Returns all policies in the given realm bypassing permission filters and SecurityIdentity.
    * Intended for internal hydration of RuleContext.
    */
   public java.util.List<Policy> getAllListIgnoreRules(String realmId) {
      dev.morphia.Datastore ds = morphiaDataStoreWrapper.getDataStore(realmId);
      dev.morphia.query.MorphiaCursor<Policy> cursor = ds.find(Policy.class).iterator();
      try (cursor) {
         return cursor.toList();
      }
   }

   /**
    * Returns effective policies: default system policies merged with database policies.
    * Database policies are always fetched fresh from the collection.
    * This method applies the same merge logic as PolicyResource.getList().
    */
   public Map<String, List<Rule>> getEffectiveRules(String realmId, List<Policy> defaultSystemPolicies) {
      Map<String, List<Rule>> rules = new HashMap<>();
      
      // First add default system policies
      if (defaultSystemPolicies != null) {
         for (Policy p : defaultSystemPolicies) {
            if (p.getRules() == null) continue;
            for (Rule r : p.getRules()) {
               String identity = extractIdentity(r, p);
               if (identity == null || identity.isBlank()) continue;
               rules.computeIfAbsent(identity, k -> new ArrayList<>()).add(r);
            }
         }
      }
      
      // Then add database policies (always fresh from collection)
      List<Policy> dbPolicies = getAllListIgnoreRules(realmId);
      if (dbPolicies != null) {
         for (Policy p : dbPolicies) {
            p.setPolicySource("POLICY_COLLECTION");
            if (p.getRules() == null) continue;
            for (Rule r : p.getRules()) {
               String identity = extractIdentity(r, p);
               if (identity == null || identity.isBlank()) continue;
               rules.computeIfAbsent(identity, k -> new ArrayList<>()).add(r);
            }
         }
      }
      
      // Sort each identity's rules by priority
      for (Map.Entry<String, List<Rule>> e : rules.entrySet()) {
         List<Rule> list = e.getValue();
         if (list != null && list.size() > 1) {
            list.sort((r1, r2) -> Integer.compare(r1.getPriority(), r2.getPriority()));
         }
      }
      
      return rules;
   }
   
   private String extractIdentity(Rule r, Policy p) {
      String identity = null;
      if (r.getSecurityURI() != null && r.getSecurityURI().getHeader() != null) {
         identity = r.getSecurityURI().getHeader().getIdentity();
      }
      if (identity == null || identity.isBlank()) {
         identity = p.getPrincipalId();
      }
      return identity;
   }
}
