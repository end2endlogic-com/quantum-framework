package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;

import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class PolicyRepo extends MorphiaRepo<Policy> {
   @ConfigProperty(name = "quantum.security.policy-store-realm")
   Optional<String> policyStoreRealm = Optional.empty();

   @ConfigProperty(name = "quantum.security.policy-admission.enabled", defaultValue = "false")
   boolean policyAdmissionEnabled;

   @Inject
   ApplicationRepo applicationRepo;

   @Inject
   FunctionalDomainRepo functionalDomainRepo;

   @Override
   public String getSecurityContextRealmId() {
      return policyStoreRealm
         .filter(value -> !value.isBlank())
         .map(String::trim)
         .orElseGet(super::getSecurityContextRealmId);
   }

   @Override
   protected void setDefaultValues(Policy policy) {
      super.setDefaultValues(policy);
      if (policyAdmissionEnabled) validatePolicyScopeAndVocabulary(policy);
   }

   private void validatePolicyScopeAndVocabulary(Policy policy) {
      String applicationId = required(policy.getApplicationId(), "Policy.applicationId is required");
      required(policy.getRealmRefName(), "Policy.realmRefName is required (use * for every realm)");
      if (!"*".equals(applicationId)
            && applicationRepo.findByRefNameWithIgnoreRules(applicationId).isEmpty()) {
         throw new IllegalArgumentException("Policy references unknown application: " + applicationId);
      }
      if ("*".equals(applicationId)) return;

      Map<String, Set<String>> vocabulary = new HashMap<>();
      for (var domain : functionalDomainRepo.findForApplicationWithIgnoreRules(applicationId)) {
         if (domain.getArea() == null || domain.getRefName() == null) continue;
         String key = axis(domain.getArea()) + "/" + axis(domain.getRefName());
         Set<String> actions = vocabulary.computeIfAbsent(key, ignored -> new java.util.HashSet<>());
         if (domain.getFunctionalActions() != null) {
            for (var action : domain.getFunctionalActions()) {
               if (action != null && action.getRefName() != null) actions.add(axis(action.getRefName()));
            }
         }
      }
      if (vocabulary.isEmpty()) {
         throw new IllegalArgumentException(
            "No functional-domain vocabulary is registered for application: " + applicationId
               + ". Seed that application's functionalDomain catalog before its policies.");
      }
      if (policy.getRules() == null) return;
      for (Rule rule : policy.getRules()) {
         if (rule == null || rule.getSecurityURI() == null || rule.getSecurityURI().getHeader() == null) {
            throw new IllegalArgumentException("Every policy rule must declare a SecurityURI header");
         }
         SecurityURIHeader header = rule.getSecurityURI().getHeader();
         if (wildcard(header.getArea()) || wildcard(header.getFunctionalDomain())) continue;
         String key = axis(header.getArea()) + "/" + axis(header.getFunctionalDomain());
         Set<String> actions = vocabulary.get(key);
         if (actions == null) {
            throw new IllegalArgumentException(
               "Policy rule '" + rule.getName() + "' references unknown functional area/domain: " + key);
         }
         if (!wildcard(header.getAction()) && !actions.contains(axis(header.getAction()))) {
            throw new IllegalArgumentException(
               "Policy rule '" + rule.getName() + "' references unknown action '"
                  + header.getAction() + "' for " + key);
         }
      }
   }

   private static String required(String value, String message) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
      return value.trim();
   }

   private static String axis(String value) {
      return value.trim().toLowerCase(Locale.ROOT);
   }

   private static boolean wildcard(String value) {
      return value == null || value.isBlank() || "*".equals(value.trim());
   }

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
    * Reads centrally stored policies for one application/operating-realm scope.
    * The datastore realm identifies where policy is stored; {@code targetRealm}
    * identifies the tenant realm to which a policy applies.
    */
   public List<Policy> getAllListIgnoreRules(String datastoreRealm,
                                             String applicationId,
                                             String targetRealm,
                                             boolean includeLegacyUnscoped) {
      return getAllListIgnoreRules(datastoreRealm).stream()
         .filter(policy -> matchesScope(policy, applicationId, targetRealm, includeLegacyUnscoped))
         .toList();
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

   public List<Policy> getPoliciesForIdentities(String datastoreRealm,
                                                 Collection<String> identities,
                                                 String applicationId,
                                                 String targetRealm,
                                                 boolean includeLegacyUnscoped) {
      return getPoliciesForIdentities(datastoreRealm, identities).stream()
         .filter(policy -> matchesScope(policy, applicationId, targetRealm, includeLegacyUnscoped))
         .toList();
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
      Set<String> identitySet = identities.stream()
         .filter(identity -> identity != null && !identity.isBlank())
         .map(identity -> identity.toLowerCase(Locale.ROOT))
         .collect(Collectors.toSet());

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

   public Map<String, List<Rule>> getEffectiveRulesForIdentities(
         String datastoreRealm,
         List<Policy> defaultSystemPolicies,
         Collection<String> identities,
         String applicationId,
         String targetRealm,
         boolean includeLegacyUnscoped) {

      List<Policy> scopedDefaults = defaultSystemPolicies == null
         ? List.of()
         : defaultSystemPolicies.stream()
            .filter(policy -> matchesScope(policy, applicationId, targetRealm, includeLegacyUnscoped))
            .toList();
      Map<String, List<Rule>> rules = effectiveRulesFromPolicies(scopedDefaults, identities, false);
      List<Policy> databasePolicies = getPoliciesForIdentities(
         datastoreRealm, identities, applicationId, targetRealm, includeLegacyUnscoped);
      mergePolicies(rules, databasePolicies, identities, true);
      sortRules(rules);
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

   public Map<String, List<Rule>> getEffectiveRules(String datastoreRealm,
                                                     List<Policy> defaultSystemPolicies,
                                                     String applicationId,
                                                     String targetRealm,
                                                     boolean includeLegacyUnscoped) {
      Map<String, List<Rule>> rules = new HashMap<>();
      mergePolicies(rules,
         defaultSystemPolicies == null ? List.of() : defaultSystemPolicies.stream()
            .filter(policy -> matchesScope(policy, applicationId, targetRealm, includeLegacyUnscoped))
            .toList(),
         null,
         false);
      mergePolicies(rules,
         getAllListIgnoreRules(datastoreRealm, applicationId, targetRealm, includeLegacyUnscoped),
         null,
         true);
      sortRules(rules);
      return rules;
   }

   private Map<String, List<Rule>> effectiveRulesFromPolicies(List<Policy> policies,
                                                               Collection<String> identities,
                                                               boolean databasePolicy) {
      Map<String, List<Rule>> rules = new HashMap<>();
      mergePolicies(rules, policies, identities, databasePolicy);
      return rules;
   }

   private void mergePolicies(Map<String, List<Rule>> rules,
                              Collection<Policy> policies,
                              Collection<String> identities,
                              boolean databasePolicy) {
      if (policies == null) return;
      Set<String> identitySet = identities == null ? null : identities.stream()
         .filter(identity -> identity != null && !identity.isBlank())
         .map(identity -> identity.toLowerCase(Locale.ROOT))
         .collect(Collectors.toSet());
      for (Policy policy : policies) {
         if (policy == null || policy.getRules() == null) continue;
         if (databasePolicy) policy.setPolicySource("POLICY_COLLECTION");
         for (Rule rule : policy.getRules()) {
            String identity = extractIdentity(rule, policy);
            if (identity == null || identity.isBlank()) continue;
            if (identitySet != null && !identitySet.contains(identity)) continue;
            rules.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(rule);
         }
      }
   }

   private void sortRules(Map<String, List<Rule>> rules) {
      for (List<Rule> list : rules.values()) {
         if (list != null && list.size() > 1) {
            list.sort((left, right) -> Integer.compare(left.getPriority(), right.getPriority()));
         }
      }
   }

   boolean matchesScope(Policy policy,
                                String applicationId,
                                String targetRealm,
                                boolean includeLegacyUnscoped) {
      return policy != null
         && matchesValue(policy.getApplicationId(), applicationId, includeLegacyUnscoped)
         && matchesValue(policy.getRealmRefName(), targetRealm, includeLegacyUnscoped);
   }

   private boolean matchesValue(String policyValue, String requestedValue, boolean includeLegacyUnscoped) {
      if (policyValue == null || policyValue.isBlank()) return includeLegacyUnscoped;
      if ("*".equals(policyValue.trim())) return true;
      return requestedValue != null && policyValue.trim().equalsIgnoreCase(requestedValue.trim());
   }
   
   private String extractIdentity(Rule r, Policy p) {
      String identity = null;
      if (r.getSecurityURI() != null && r.getSecurityURI().getHeader() != null) {
         identity = r.getSecurityURI().getHeader().getIdentity();
      }
      if (identity == null || identity.isBlank()) {
         identity = p.getPrincipalId();
      }
      return identity == null ? null : identity.toLowerCase(Locale.ROOT);
   }
}
