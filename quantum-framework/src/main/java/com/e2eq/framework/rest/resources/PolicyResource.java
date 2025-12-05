package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.io.YamlPolicyItem;
import com.e2eq.framework.securityrules.io.YamlPolicyLoader;
import com.e2eq.framework.securityrules.io.YamlRuleMapper;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.inject.Inject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Path("/security/permission/policies")
public class PolicyResource extends BaseResource<Policy, PolicyRepo>{
   @Inject
   RuleContext ruleContext;

   @Inject
   SecurityUtils securityUtils;

   protected PolicyResource (PolicyRepo repo) {
      super(repo);
   }

   @Override
   public com.e2eq.framework.rest.models.Collection<Policy> getList(
           jakarta.ws.rs.core.HttpHeaders headers,
           int skip,
           int limit,
           String filter,
           String sort,
           String projection) {

      // Get default system policies
      List<Policy> defaultPolicies = ruleContext.getDefaultSystemPolicies();

      // Apply filtering to default policies using QueryPredicates if filter is present
      List<Policy> filteredDefaults = new ArrayList<>();
      if (filter != null && !filter.isEmpty()) {
         filteredDefaults = applyFilterToPolicies(defaultPolicies, filter);
      } else {
         filteredDefaults = new ArrayList<>(defaultPolicies);
      }

      // Get database policies using parent implementation
      com.e2eq.framework.rest.models.Collection<Policy> dbCollection = super.getList(headers, skip, limit, filter, sort, projection);

      // Merge: default policies first, then database policies
      List<Policy> mergedList = new ArrayList<>();
      mergedList.addAll(filteredDefaults);
      mergedList.addAll(dbCollection.getRows());

      // Adjust count to include default policies
      long totalCount = filteredDefaults.size() + dbCollection.getTotalCount();

      // Create merged collection
      com.e2eq.framework.rest.models.Collection<Policy> mergedCollection =
         new com.e2eq.framework.rest.models.Collection<>(mergedList, skip, limit, filter, totalCount);

      mergedCollection.setFilter(filter);
      String realmId = headers.getHeaderString("X-Realm");
      mergedCollection.setRealm(realmId == null ? repo.getDatabaseName() : realmId);

      return mergedCollection;
   }

   private List<Policy> applyFilterToPolicies(List<Policy> policies, String filter) {
      if (filter == null || filter.isEmpty()) {
         return new ArrayList<>(policies);
      }

      List<Policy> filtered = new ArrayList<>();
      try {
         // Use QueryPredicates to compile filter
         Class<?> qp = Class.forName("com.e2eq.framework.query.QueryPredicates");
         java.lang.reflect.Method m = qp.getMethod("compilePredicate", String.class, Map.class, Map.class);

         @SuppressWarnings("unchecked")
         java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode> predicate =
            (java.util.function.Predicate<com.fasterxml.jackson.databind.JsonNode>) m.invoke(null, filter, Collections.emptyMap(), Collections.emptyMap());

         com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
         for (Policy policy : policies) {
            com.fasterxml.jackson.databind.JsonNode node = mapper.valueToTree(policy);
            if (predicate.test(node)) {
               filtered.add(policy);
            }
         }
      } catch (Exception e) {
         // If filtering fails, return all policies
         io.quarkus.logging.Log.warnf(e, "Failed to apply filter to default policies: %s", filter);
         return new ArrayList<>(policies);
      }

      return filtered;
   }

   @POST
   @Path("/refreshRuleContext")
   public Response refreshRuleContext(@HeaderParam("X-Realm") String realm) {
      String effectiveRealm = (realm == null || realm.isBlank()) ? ruleContext.getDefaultRealm() : realm;
      ruleContext.reloadFromRepo(effectiveRealm);
      return Response.ok().build();
   }

   @POST
   @Path("/import")
   @Consumes({MediaType.TEXT_PLAIN, "application/yaml", "text/yaml"})
   @Produces(MediaType.APPLICATION_JSON)
   @RolesAllowed("admin")
   public Response importPolicies(@HeaderParam("X-Realm") String realm, String yamlPayload) {
      String effectiveRealm = (realm == null || realm.isBlank()) ? ruleContext.getDefaultRealm() : realm;

      if (yamlPayload == null || yamlPayload.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST)
                 .entity(Map.of("error", "YAML payload is required"))
                 .build();
      }

      List<YamlPolicyItem> yamlPolicies;
      try {
         YamlPolicyLoader loader = new YamlPolicyLoader();
         yamlPolicies = loader.load(yamlPayload);
      } catch (IOException ex) {
         return Response.status(Response.Status.BAD_REQUEST)
                 .entity(Map.of(
                         "error", "Malformed YAML",
                         "details", ex.getMessage()))
                 .build();
      }

      List<Map<String, Object>> created = new ArrayList<>();
      List<Map<String, Object>> updated = new ArrayList<>();
      List<Map<String, Object>> errors = new ArrayList<>();

      int row = 0;
      for (YamlPolicyItem item : yamlPolicies) {
         row++;
         if (item == null) {
            errors.add(errorEntry(row, null, "Entry was null"));
            continue;
         }

         String refName = item.refName;
         try {
            PolicyImportResult result = upsertPolicy(effectiveRealm, item);
            Map<String, Object> entry = successEntry(result.policy(), row, result.created());
            if (result.created()) {
               created.add(entry);
            } else {
               updated.add(entry);
            }
         } catch (IllegalArgumentException ex) {
            errors.add(errorEntry(row, refName, ex.getMessage()));
         } catch (Exception ex) {
            errors.add(errorEntry(row, refName, "Failed to import policy: " + ex.getMessage()));
         }
      }

      if (!created.isEmpty() || !updated.isEmpty()) {
         ruleContext.reloadFromRepo(effectiveRealm);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("realm", effectiveRealm);
      response.put("totalProcessed", yamlPolicies.size());
      response.put("createdCount", created.size());
      response.put("updatedCount", updated.size());
      response.put("errorCount", errors.size());
      response.put("created", created);
      response.put("updated", updated);
      response.put("errors", errors);

      return Response.ok(response).build();
   }

   private PolicyImportResult upsertPolicy(String realm, YamlPolicyItem item) {
      if (item.refName == null || item.refName.isBlank()) {
         throw new IllegalArgumentException("refName is required");
      }
      if (item.principalId == null || item.principalId.isBlank()) {
         throw new IllegalArgumentException("principalId is required");
      }

      Optional<Policy> existing = repo.findByRefName(realm, item.refName);
      Policy policy = existing.orElseGet(Policy::new);
      boolean created = existing.isEmpty();

      policy.setRefName(item.refName);
      policy.setDisplayName((item.displayName != null && !item.displayName.isBlank()) ? item.displayName : item.refName);
      policy.setDescription(item.description);
      policy.setPrincipalId(item.principalId);
      policy.setPrincipalType(resolvePrincipalType(item.principalType, existing.map(Policy::getPrincipalType).orElse(null)));

      if (policy.getDataDomain() == null) {
         policy.setDataDomain(securityUtils.getSystemDataDomain());
      }

      List<Rule> rules;
      if (item.rules != null && !item.rules.isEmpty()) {
         rules = YamlRuleMapper.toRules(item.rules);
      } else if (item.legacyRules != null && !item.legacyRules.isEmpty()) {
         List<Rule> copy = new ArrayList<>();
         for (Rule r : item.legacyRules) {
            if (r != null) {
               copy.add(r);
            }
         }
         rules = copy;
      } else {
         rules = Collections.emptyList();
      }
      policy.setRules(new ArrayList<>(rules));

      repo.save(realm, policy);

      return new PolicyImportResult(policy, created);
   }

   private Policy.PrincipalType resolvePrincipalType(String raw, Policy.PrincipalType existing) {
      if (raw == null || raw.isBlank()) {
         return existing != null ? existing : Policy.PrincipalType.ROLE;
      }
      try {
         return Policy.PrincipalType.valueOf(raw.trim().toUpperCase());
      } catch (IllegalArgumentException ex) {
         throw new IllegalArgumentException("Invalid principalType '" + raw + "'");
      }
   }

   private Map<String, Object> successEntry(Policy policy, int row, boolean created) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("row", row);
      entry.put("refName", policy.getRefName());
      entry.put("principalId", policy.getPrincipalId());
      entry.put("ruleCount", policy.getRules() != null ? policy.getRules().size() : 0);
      entry.put("status", created ? "created" : "updated");
      return entry;
   }

   private Map<String, Object> errorEntry(int row, String refName, String message) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("row", row);
      if (refName != null) {
         entry.put("refName", refName);
      }
      entry.put("error", message);
      return entry;
   }

   private record PolicyImportResult(Policy policy, boolean created) {}
}
