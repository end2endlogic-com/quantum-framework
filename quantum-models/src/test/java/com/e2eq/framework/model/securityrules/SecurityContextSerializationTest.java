package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityContextSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void principalContextRoundTripsThroughBuilder() {
        PrincipalContext original = principalContext();

        PrincipalContext restored = mapper.convertValue(original, PrincipalContext.class);

        assertEquals("shared", restored.getDefaultRealm());
        assertEquals("system-manager", restored.getApplicationId());
        assertEquals("user-123", restored.getUserId());
        assertEquals("subject-123", restored.getSubjectId());
        assertArrayEquals(new String[]{"administrator", "operator"}, restored.getRoles());
        assertEquals("authenticated", restored.getScope());
        assertEquals("subject-admin", restored.getImpersonatedBySubject());
        assertEquals("admin", restored.getImpersonatedByUserId());
        assertEquals("subject-service", restored.getActingOnBehalfOfSubject());
        assertEquals("service-user", restored.getActingOnBehalfOfUserId());
        assertEquals(Map.of("admin", "shared"), restored.getArea2RealmOverrides());
        assertEquals(dataDomainPolicy(), restored.getDataDomainPolicy());
        assertEquals(originalDataDomain(), restored.getOriginalDataDomain());
        assertEquals(domainContext(), restored.getDomainContext());
        assertTrue(restored.isRealmOverrideActive());
        assertEquals("us-east4", restored.getCustomProperty("deploymentRegion"));
        assertEquals(dataDomain(), restored.getDataDomain());
    }

    @Test
    void resourceContextRoundTripsThroughBuilder() {
        ResourceContext original = resourceContext();

        ResourceContext restored = mapper.convertValue(original, ResourceContext.class);

        assertEquals("shared", restored.getRealm());
        assertEquals("system-manager", restored.getApplicationId());
        assertEquals("admin", restored.getArea());
        assertEquals("tenant", restored.getFunctionalDomain());
        assertEquals("list", restored.getAction());
        assertNull(restored.getResourceId());
        assertNull(restored.getOwnerId());
        assertEquals(dataDomain(), restored.getDataDomain());
        assertEquals(Map.of("resourceType", "realm"), restored.getAttributes());
    }

    @Test
    void securityCheckResponseRoundTripsTheCompleteObjectGraph() {
        Rule rule = new Rule();
        rule.setId(42);
        rule.setName("allow-shared-realm");
        rule.setSecurityURI(new SecurityURI());
        rule.getSecurityURI().getBody().setResourceId(null);
        rule.setEffect(RuleEffect.ALLOW);
        rule.setPriority(100);

        RuleResult ruleResult = new RuleResult(rule);
        ruleResult.setDeterminedEffect(RuleDeterminedEffect.ALLOW);

        MatchEvent matchEvent = MatchEvent.builder()
                .principalUriString("shared|administrator")
                .ruleUriString("shared|admin|tenant|list")
                .ruleName("allow-shared-realm")
                .matched(true)
                .build();

        SecurityCheckResponse original = new SecurityCheckResponse(principalContext(), resourceContext());
        original.setMatchEvents(List.of(matchEvent));
        original.setEvaluatedRules(List.of(rule));
        original.setMatchedRuleResults(List.of(ruleResult));
        original.setFinalEffect(RuleEffect.ALLOW);
        original.setDecision("ALLOW");
        original.setDecisionScope("EXACT");
        original.setExcludedFields(List.of("description", "nested.secret"));

        assertEquals("*:*:*:*",
                mapper.valueToTree(original).at("/evaluatedRules/0/securityURI/header/URIString").asText());
        assertEquals("*:*:*:*:*:*:*",
                mapper.valueToTree(original).at("/evaluatedRules/0/securityURI/body/URIString").asText());

        SecurityCheckResponse restored = mapper.convertValue(original, SecurityCheckResponse.class);

        assertEquals("subject-123", restored.getPrincipalContext().getSubjectId());
        assertEquals("list", restored.getResourceContext().getAction());
        assertEquals("allow-shared-realm", restored.getMatchEvents().get(0).getRuleName());
        assertEquals(42, restored.getEvaluatedRules().get(0).getId());
        assertNull(restored.getEvaluatedRules().get(0).getSecurityURI().getBody().getResourceId());
        assertEquals(RuleDeterminedEffect.ALLOW,
                restored.getMatchedRuleResults().get(0).getDeterminedEffect());
        assertEquals("ALLOW", restored.getDecision());
        assertEquals(RuleEffect.ALLOW, restored.getFinalEffect());
        assertEquals(List.of("description", "nested.secret"), restored.getExcludedFields());
    }

    private PrincipalContext principalContext() {
        return new PrincipalContext.Builder()
                .withDefaultRealm("shared")
                .withApplicationId("system-manager")
                .withDataDomain(dataDomain())
                .withUserId("user-123")
                .withSubjectId("subject-123")
                .withRoles(new String[]{"administrator", "operator"})
                .withScope("authenticated")
                .withImpersonatedBySubject("subject-admin")
                .withImpersonatedByUserId("admin")
                .withActingOnBehalfOfSubject("subject-service")
                .withActingOnBehalfOfUserId("service-user")
                .withArea2RealmOverrides(Map.of("admin", "shared"))
                .withDataDomainPolicy(dataDomainPolicy())
                .withRealmOverrideActive(true)
                .withOriginalDataDomain(originalDataDomain())
                .withDomainContext(domainContext())
                .withCustomProperty("deploymentRegion", "us-east4")
                .build();
    }

    private ResourceContext resourceContext() {
        return new ResourceContext.Builder()
                .withRealm("shared")
                .withApplicationId("system-manager")
                .withArea("admin")
                .withFunctionalDomain("tenant")
                .withAction("list")
                .withResourceId(null)
                .withOwnerId(null)
                .withDataDomain(dataDomain())
                .withAttribute("resourceType", "realm")
                .build();
    }

    private DataDomain dataDomain() {
        return new DataDomain("helixor", "account-1", "tenant-1", 4, "user-123");
    }

    private DataDomain originalDataDomain() {
        return new DataDomain("helixor", "account-1", "tenant-original", 2, "user-123");
    }

    private DomainContext domainContext() {
        return new DomainContext("tenant-1", "shared", "helixor", "account-1", 4);
    }

    private DataDomainPolicy dataDomainPolicy() {
        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(Map.of());
        return policy;
    }
}
