package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;

import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    * Returns policies matching the given identities (user ID, roles).
    * This is an optimized query that reduces the number of policies fetched from the database.
    * Wildcard matching for area/domain/action is handled at rule evaluation time via WildCardMatcher,
    * not at the database query level.
    *
    * @param realmId the realm to query
    * @param identities the set of identities to match (userId + roles)
    * @return policies where principalId matches one of the identities, or empty list if no identities
    */
   public List<Policy> getPoliciesForIdentities(String realmId, Collection<String> identities) {
      // Guard: empty identities means no matching policies - skip DB call
      if (identities == null || identities.isEmpty()) {
         return new ArrayList<>();
      }

      dev.morphia.Datastore ds = morphiaDataStoreWrapper.getDataStore(realmId);

      dev.morphia.query.MorphiaCursor<Policy> cursor = ds.find(Policy.class)
         .filter(Filters.in("principalId", identities))
         .iterator();
      try (cursor) {
         return cursor.toList();
      }
   }

   /**
    * Returns effective rules for specific identities only.
    * This is an optimized version that queries only policies matching the given identities.
    * Used when caching is disabled to reduce database load.
    *
    * @param realmId the realm to query
    * @param defaultSystemPolicies system policies to merge (already filtered by caller if needed)
    * @param identities the set of identities (userId + roles) to fetch rules for
    * @return map of identity to sorted list of rules
    */
   public Map<String, List<Rule>> getEffectiveRulesForIdentities(
         String realmId,
         List<Policy> defaultSystemPolicies,
         Collection<String> identities) {

      Map<String, List<Rule>> rules = new HashMap<>();
      Set<String> identitySet = new java.util.HashSet<>(identities);

      // First add default system policies (filter to matching identities)
      if (defaultSystemPolicies != null) {
         for (Policy p : defaultSystemPolicies) {
            if (p.getRules() == null) continue;
            for (Rule r : p.getRules()) {
               String identity = extractIdentity(r, p);
               if (identity == null || identity.isBlank()) continue;
               // Only include if identity matches one of the requested identities
               if (identitySet.contains(identity)) {
                  rules.computeIfAbsent(identity, k -> new ArrayList<>()).add(r);
               }
            }
         }
      }

      // Then add database policies (optimized query)
      List<Policy> dbPolicies = getPoliciesForIdentities(realmId, identitySet);
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

   /**
    * Returns effective policies: default system policies merged with database policies.
    * Database policies are always fetched fresh from the collection.
    * This method applies the same merge logic as PolicyResource.getList().
    *
    * @deprecated Use {@link #getEffectiveRulesForIdentities} for better performance when identities are known
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
