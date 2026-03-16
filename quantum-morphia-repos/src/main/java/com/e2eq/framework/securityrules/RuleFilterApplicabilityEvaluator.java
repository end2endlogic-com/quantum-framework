package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.FilterJoinOp;
import com.e2eq.framework.model.securityrules.MatchEvent;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

final class RuleFilterApplicabilityEvaluator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleVariableBundleResolver variableBundleResolver;

    RuleFilterApplicabilityEvaluator(RuleVariableBundleResolver variableBundleResolver) {
        this.variableBundleResolver = variableBundleResolver;
    }

    Optional<Boolean> evaluate(
            PrincipalContext pcontext,
            ResourceContext rcontext,
            Rule rule,
            Class<? extends UnversionedBaseModel> modelClass,
            Object resourceInstance,
            MatchEvent matchEvent) {

        if (matchEvent != null && rule != null) {
            matchEvent.setFilterAndString(rule.getAndFilterString());
            matchEvent.setFilterOrString(rule.getOrFilterString());
            matchEvent.setFilterJoinOp(rule.getJoinOp() != null ? rule.getJoinOp().name() : "AND");
            matchEvent.setFilterEvaluated(false);
            matchEvent.setFilterResult(null);
            matchEvent.setFilterReason(null);
        }

        if (resourceInstance == null) {
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("No resource provided; evaluator disabled");
            }
            return Optional.empty();
        }

        boolean hasAnd = rule != null && StringUtils.isNotBlank(rule.getAndFilterString());
        boolean hasOr = rule != null && StringUtils.isNotBlank(rule.getOrFilterString());
        if (!hasAnd && !hasOr) {
            return Optional.of(true);
        }

        JsonNode factsNode;
        try {
            factsNode = mapper.valueToTree(buildFacts(pcontext, rcontext, resourceInstance));
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Failed to build facts for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Facts building failed");
            }
            return Optional.empty();
        }

        MorphiaUtils.VariableBundle vars;
        try {
            vars = variableBundleResolver.resolveVariableBundle(pcontext, rcontext, modelClass);
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Failed to resolve variable bundle for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Variable resolution failed");
            }
            return Optional.empty();
        }

        Predicate<JsonNode> andPredicate = null;
        Predicate<JsonNode> orPredicate = null;
        try {
            if (hasAnd) {
                andPredicate = tryCompilePredicate(rule.getAndFilterString(), vars.strings, vars.objects).orElse(null);
            }
            if (hasOr) {
                orPredicate = tryCompilePredicate(rule.getOrFilterString(), vars.strings, vars.objects).orElse(null);
            }
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Predicate compilation failed for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Predicate compilation failed");
            }
            return Optional.empty();
        }
        if ((hasAnd && andPredicate == null) || (hasOr && orPredicate == null)) {
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Predicate compilation unavailable");
            }
            return Optional.empty();
        }

        boolean andOk = true;
        boolean orOk = true;
        try {
            if (andPredicate != null) {
                andOk = andPredicate.test(factsNode);
            }
            if (orPredicate != null) {
                orOk = orPredicate.test(factsNode);
            }
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] Predicate evaluation failed for rule '%s'", rule != null ? rule.getName() : "<null>");
            }
            if (matchEvent != null) {
                matchEvent.setFilterEvaluated(false);
                matchEvent.setFilterResult(null);
                matchEvent.setFilterReason("Predicate evaluation failed");
            }
            return Optional.empty();
        }

        boolean result;
        if (hasAnd && hasOr) {
            FilterJoinOp joinOp = rule.getJoinOp() != null ? rule.getJoinOp() : FilterJoinOp.AND;
            result = joinOp == FilterJoinOp.OR ? andOk || orOk : andOk && orOk;
        } else if (hasAnd) {
            result = andOk;
        } else {
            result = orOk;
        }

        if (matchEvent != null) {
            matchEvent.setFilterEvaluated(true);
            matchEvent.setFilterResult(result);
            matchEvent.setFilterReason(null);
        }
        return Optional.of(result);
    }

    private Map<String, Object> buildFacts(PrincipalContext pcontext, ResourceContext rcontext, Object resourceInstance) {
        Map<String, Object> facts = new HashMap<>();

        Map<String, Object> rcMap = new HashMap<>();
        rcMap.put("area", rcontext != null ? rcontext.getArea() : null);
        rcMap.put("functionalDomain", rcontext != null ? rcontext.getFunctionalDomain() : null);
        rcMap.put("action", rcontext != null ? rcontext.getAction() : null);
        rcMap.put("resourceId", rcontext != null ? rcontext.getResourceId() : null);
        facts.put("rcontext", rcMap);

        Map<String, Object> ddMap = new HashMap<>();
        if (pcontext != null && pcontext.getDataDomain() != null) {
            ddMap.put("orgRefName", pcontext.getDataDomain().getOrgRefName());
            ddMap.put("accountNum", pcontext.getDataDomain().getAccountNum());
            ddMap.put("tenantId", pcontext.getDataDomain().getTenantId());
            ddMap.put("dataSegment", pcontext.getDataDomain().getDataSegment());
            ddMap.put("ownerId", pcontext.getDataDomain().getOwnerId());
        }
        facts.put("dataDomain", ddMap);

        if (resourceInstance instanceof Map<?, ?> resourceMap) {
            facts.put("resource", resourceMap);
        } else if (resourceInstance != null) {
            facts.put("resource", mapper.valueToTree(resourceInstance));
        }

        return facts;
    }

    @SuppressWarnings("unchecked")
    private Optional<Predicate<JsonNode>> tryCompilePredicate(
            String query,
            Map<String, String> vars,
            Map<String, Object> objectVars) {
        try {
            Class<?> queryPredicates = Class.forName("com.e2eq.framework.query.runtime.QueryPredicates");
            java.lang.reflect.Method compilePredicate = queryPredicates.getMethod("compilePredicate", String.class, Map.class, Map.class);
            Object predicate = compilePredicate.invoke(null, query, vars, objectVars);
            return Optional.of((Predicate<JsonNode>) predicate);
        } catch (Throwable t) {
            if (Log.isDebugEnabled()) {
                Log.debugf(t, "[Evaluator] QueryPredicates not available or invocation failed; falling back");
            }
            return Optional.empty();
        }
    }
}
