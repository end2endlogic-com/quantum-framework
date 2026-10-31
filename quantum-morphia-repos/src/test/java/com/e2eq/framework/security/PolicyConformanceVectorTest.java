package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.FilterJoinOp;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.security.runtime.RuleContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the policy conformance vectors (src/test/resources/policy-conformance-vectors) against RuleContext.
 *
 * The vectors are the canonical, implementation-neutral description of the policy decision: another
 * implementation of the same semantics must pass the same files unchanged. Each vector gives a principal,
 * a resource and the rules in force; the test asserts the final effect, the surfaced filter constraints
 * and the excluded field paths.
 */
public class PolicyConformanceVectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/policy-conformance-vectors/";

    @TestFactory
    Stream<DynamicTest> vectors() throws Exception {
        Map<?, ?> index = read("index.json");
        List<?> names = (List<?>) index.get("vectors");
        assertNotNull(names, "index.json lists the vectors");
        return names.stream().map(name -> DynamicTest.dynamicTest(String.valueOf(name), () -> run(String.valueOf(name))));
    }

    private static Map<?, ?> read(String file) throws Exception {
        try (InputStream in = PolicyConformanceVectorTest.class.getResourceAsStream(ROOT + file)) {
            assertNotNull(in, "missing vector file " + file);
            return JSON.readValue(in, Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static void run(String name) throws Exception {
        Map<String, Object> vector = (Map<String, Object>) read(name + ".json");
        RuleContext ruleContext = new RuleContext();
        ruleContext.clear();
        for (Object raw : (List<?>) vector.get("rules")) {
            Rule rule = rule((Map<String, Object>) raw);
            ruleContext.addRule(rule.getSecurityURI().getHeader(), rule);
        }
        PrincipalContext principal = principal((Map<String, Object>) vector.get("principal"));
        ResourceContext resource = resource((Map<String, Object>) vector.get("resource"));
        RuleEffect defaultEffect = RuleEffect.valueOf(String.valueOf(vector.getOrDefault("defaultEffect", "DENY")));

        SecurityCheckResponse response = ruleContext.checkRules(principal, resource, defaultEffect);
        Map<String, Object> expected = (Map<String, Object>) vector.get("expected");

        assertEquals(expected.get("finalEffect"), response.getFinalEffect().name(), name + ": finalEffect");
        List<Map<String, Object>> constraints = new ArrayList<>();
        if (response.getFilterConstraints() != null) {
            for (SecurityCheckResponse.RuleFilterInfo info : response.getFilterConstraints()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ruleName", info.getRuleName());
                row.put("andFilterString", info.getAndFilterString());
                row.put("orFilterString", info.getOrFilterString());
                row.put("joinOp", info.getJoinOp());
                constraints.add(row);
            }
        }
        assertEquals(expected.get("filterConstraints"), constraints, name + ": filterConstraints");
        List<String> excluded = new ArrayList<>(ruleContext.getExcludedFieldPaths(principal, resource));
        assertEquals(expected.get("excludedFields"), excluded, name + ": excludedFields");
    }

    @Test
    void aRuleWithAScriptIsRefused() {
        for (String key : new String[] {"preconditionScript", "postconditionScript"}) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("name", "scripted");
            raw.put(key, "true");
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> rule(raw));
            assertTrue(refused.getMessage().contains(key), refused.getMessage());
        }
    }

    /** Scripts are out of scope for the vectors: a rule that has one is refused, never built without it. */
    @SuppressWarnings("unchecked")
    private static Rule rule(Map<String, Object> raw) {
        for (String key : new String[] {"preconditionScript", "postconditionScript"}) {
            if (raw.get(key) != null) {
                throw new IllegalArgumentException("rule " + raw.get("name") + " has a " + key
                        + "; scripts are out of scope for the conformance vectors");
            }
        }
        Map<String, Object> h = (Map<String, Object>) raw.get("header");
        Map<String, Object> b = (Map<String, Object>) raw.get("body");
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity(str(h, "identity"))
                .withArea(str(h, "area"))
                .withFunctionalDomain(str(h, "functionalDomain"))
                .withAction(str(h, "action"))
                .build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm(str(b, "realm"))
                .withOrgRefName(str(b, "orgRefName"))
                .withAccountNumber(str(b, "accountNumber"))
                .withTenantId(str(b, "tenantId"))
                .withDataSegment(str(b, "dataSegment"))
                .withOwnerId(str(b, "ownerId"))
                .withResourceId(str(b, "resourceId"))
                .build();
        Rule.Builder rule = new Rule.Builder()
                .withName(str(raw, "name"))
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.valueOf(str(raw, "effect")))
                .withPriority(((Number) raw.getOrDefault("priority", 10)).intValue())
                .withFinalRule(Boolean.TRUE.equals(raw.get("finalRule")));
        if (raw.get("andFilterString") != null) rule.withAndFilterString(str(raw, "andFilterString"));
        if (raw.get("orFilterString") != null) rule.withOrFilterString(str(raw, "orFilterString"));
        if (raw.get("joinOp") != null) rule.withJoinOp(FilterJoinOp.valueOf(str(raw, "joinOp")));
        if (raw.get("excludedFields") != null) rule.withExcludedFields((List<String>) raw.get("excludedFields"));
        return rule.build();
    }

    @SuppressWarnings("unchecked")
    private static PrincipalContext principal(Map<String, Object> raw) {
        Map<String, Object> d = (Map<String, Object>) raw.get("dataDomain");
        DataDomain dataDomain = new DataDomain(str(d, "orgRefName"), str(d, "accountNum"), str(d, "tenantId"),
                ((Number) d.getOrDefault("dataSegment", 0)).intValue(), str(d, "ownerId"));
        List<String> roles = (List<String>) raw.get("roles");
        return new PrincipalContext.Builder()
                .withDefaultRealm(str(raw, "defaultRealm"))
                .withDataDomain(dataDomain)
                .withUserId(str(raw, "userId"))
                .withRoles(roles.toArray(new String[0]))
                .build();
    }

    private static ResourceContext resource(Map<String, Object> raw) {
        ResourceContext.Builder resource = new ResourceContext.Builder()
                .withArea(str(raw, "area"))
                .withFunctionalDomain(str(raw, "functionalDomain"))
                .withAction(str(raw, "action"))
                .withOwnerId(str(raw, "ownerId"));
        if (raw.get("resourceId") != null) resource.withResourceId(str(raw, "resourceId"));
        return resource.build();
    }

    private static String str(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
