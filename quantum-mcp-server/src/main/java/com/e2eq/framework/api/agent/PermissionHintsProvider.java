package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides discoverable permission-check and policy hints for agents and developers.
 * Used by GET /api/agent/permission-hints so the LLM can answer "can role X perform
 * action Y on entity Z?", "if not, why not?" (winningRuleName), suggest rule changes,
 * and generate least-privilege rules for a scenario.
 *
 * @see AgentResource
 * @see com.e2eq.framework.rest.resources.PermissionResource
 */
@ApplicationScoped
public class PermissionHintsProvider {

    /**
     * Returns permission API summary, area/domain/action mapping, example check requests,
     * and "did you know" hints so agents can answer permission questions and suggest rule changes.
     *
     * @return structured hints (checkEndpoint, evaluateEndpoint, areaDomainActionMapping, exampleCheckRequests, didYouKnow)
     */
    public PermissionHintsResponse getHints() {
        PermissionHintsResponse response = new PermissionHintsResponse();
        response.checkEndpoint = "POST /system/permissions/check";
        response.evaluateEndpoint = "POST /system/permissions/evaluate";
        response.areaDomainActionMapping = buildAreaDomainActionMapping();
        response.exampleCheckRequests = buildExampleCheckRequests();
        response.didYouKnow = buildDidYouKnow();
        return response;
    }

    private static List<Map<String, Object>> buildAreaDomainActionMapping() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(map(
            "description", "Query Gateway (agent tools: query_find, query_save, etc.)",
            "area", "integration",
            "functionalDomain", "query",
            "actions", List.of("listRootTypes", "plan", "find", "save", "delete", "deleteMany")
        ));
        list.add(map(
            "description", "Permission check and evaluate (system)",
            "area", "system",
            "functionalDomain", "permission",
            "actions", List.of("check", "check-with-index", "evaluate", "roleProvenance")
        ));
        list.add(map(
            "description", "Migration indexes (example)",
            "area", "MIGRATION",
            "functionalDomain", "INDEXES",
            "actions", List.of("APPLY_ALL_INDEXES", "APPLY_INDEXES", "APPLY_INDEXES_COLLECTION")
        ));
        return list;
    }

    private static List<Map<String, Object>> buildExampleCheckRequests() {
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(map(
            "intent", "Can role ADMIN perform find on Location (via Query Gateway)?",
            "body", Map.of(
                "identity", "ADMIN",
                "area", "integration",
                "functionalDomain", "query",
                "action", "find",
                "realm", "defaultRealm"
            ),
            "responseFields", "decision (ALLOW|DENY), winningRuleName (rule that determined outcome), decisionScope, finalEffect"
        ));
        examples.add(map(
            "intent", "Can user support@acme.com perform save on Order?",
            "body", Map.of(
                "identity", "support@acme.com",
                "area", "integration",
                "functionalDomain", "query",
                "action", "save",
                "realm", "acme-corp"
            ),
            "responseFields", "decision, winningRuleName (if DENY, this is the rule that caused the denial), scopedConstraints (if SCOPED)"
        ));
        examples.add(map(
            "intent", "Can role SUPPORT perform listRootTypes?",
            "body", Map.of(
                "identity", "SUPPORT",
                "area", "integration",
                "functionalDomain", "query",
                "action", "listRootTypes",
                "realm", "defaultRealm"
            ),
            "responseFields", "decision, winningRuleName"
        ));
        return examples;
    }

    private static List<Map<String, Object>> buildDidYouKnow() {
        List<Map<String, Object>> hints = new ArrayList<>();
        hints.add(map(
            "title", "Use the check API to determine if a role can perform an action on an entity",
            "body", "Call POST /system/permissions/check with identity (userId or role), area, functionalDomain, and action. Map 'entity' to the REST capability: for Query Gateway use area=integration, functionalDomain=query, action=find|save|delete|listRootTypes|plan|deleteMany. Response decision is ALLOW or DENY.",
            "example", "Can role ABC perform find on Location? → check(identity=ABC, area=integration, functionalDomain=query, action=find)"
        ));
        hints.add(map(
            "title", "If denied, winningRuleName identifies the rule that caused the denial",
            "body", "When decision is DENY, the response field winningRuleName is the name of the rule that determined the outcome (e.g. default-deny or a specific DENY rule). Use it to answer 'if not, why not?' and to suggest which rule to adjust or which ALLOW rule to add.",
            "example", "Decision DENY, winningRuleName=default-deny → 'No. The default-deny rule caused the denial; add an ALLOW rule for this identity/area/domain/action with priority < 10000.'"
        ));
        hints.add(map(
            "title", "Use the evaluate API to get the full allow/deny matrix for an identity",
            "body", "Call POST /system/permissions/evaluate with identity (and optional realm, area, functionalDomain, action filters). Response includes allow/deny per (area, domain, action) and decisions[area][domain][action] with effect, rule (winning rule name), priority. Use to compare 'required APIs for scenario' vs 'what identity has' and identify gaps.",
            "example", "Evaluate(identity=SUPPORT) → decisions[integration][query][find].effect=DENY, rule=default-deny → gap for find"
        ));
        hints.add(map(
            "title", "Least-privilege for a scenario: required APIs vs policies, then suggest minimal ALLOW rules",
            "body", "1) List (area, functionalDomain, action) pairs needed for the scenario. 2) Call evaluate for the identity that would execute. 3) Gaps = required pairs that are DENY or missing. 4) For each gap, suggest one ALLOW rule (identity, area, functionalDomain, action, priority). Prefer minimal scope (only the actions needed) rather than broad wildcards.",
            "example", "Scenario: support lists locations and finds orders. Required: (integration, query, listRootTypes), (integration, query, find). Evaluate(SUPPORT) → find DENY. Suggest: add ALLOW rule identity=SUPPORT, area=integration, functionalDomain=query, action=find (or listRootTypes,find), priority=500."
        ));
        return hints;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @RegisterForReflection
    public static class PermissionHintsResponse {
        /** Check API endpoint. */
        public String checkEndpoint;
        /** Evaluate API endpoint. */
        public String evaluateEndpoint;
        /** How to map entity/action to area, functionalDomain, action (e.g. Query Gateway). */
        public List<Map<String, Object>> areaDomainActionMapping;
        /** Example check request bodies by intent. */
        public List<Map<String, Object>> exampleCheckRequests;
        /** "Did you know" hints for permission check, denial reason, evaluate, least-privilege. */
        public List<Map<String, Object>> didYouKnow;
    }
}
