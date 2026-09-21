package com.e2eq.ontology.policy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.security.runtime.RuleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptHelpersJavaScriptExecutionTest {

    private RuleContext ruleContext;
    private PrincipalContext principalContext;
    private ResourceContext resourceContext;

    @BeforeEach
    void setUp() {
        System.setProperty("quantum.security.scripting.enabled", "true");
        System.setProperty("quantum.security.scripting.allowAllAccess", "false");

        ruleContext = new RuleContext();
        ruleContext.clear();

        DataDomain dd = new DataDomain("org1", "acct1", "tenant1", 0, "u1@example.com");
        principalContext = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(dd)
                .withUserId("u1@example.com")
                .withRoles(new String[]{"admin"})
                .build();

        // Populate edges containing properties
        List<Map<String, Object>> edges = new ArrayList<>();

        Map<String, Object> edge1 = new HashMap<>();
        edge1.put("src", "u1@example.com");
        edge1.put("p", "assignedTo");
        edge1.put("dst", "loc1");
        edge1.put("inferred", false);
        edge1.put("props", Map.of("role", "PRIMARY", "status", "ACTIVE"));

        Map<String, Object> edge2 = new HashMap<>();
        edge2.put("src", "u1@example.com");
        edge2.put("p", "assignedTo");
        edge2.put("dst", "loc2");
        edge2.put("inferred", false);
        edge2.put("props", Map.of("role", "BACKUP", "status", "INACTIVE"));

        edges.add(edge1);
        edges.add(edge2);

        resourceContext = new ResourceContext.Builder()
                .withRealm("test-realm")
                .withArea("ops")
                .withFunctionalDomain("orders")
                .withAction("view")
                .withResourceId("order-1")
                .withOwnerId("u1@example.com")
                .withAttribute("edges", edges)
                .build();
    }

    @Test
    void testHasEdgeJavaScript2ArgAnd3ArgObjectFilter() {
        // 2-arg backward compat
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1') === true"));

        // 3-arg object filter matching
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1', { role: 'PRIMARY' }) === true"));

        // 3-arg object filter mismatch
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1', { role: 'BACKUP' }) === false"));

        // 3-arg multiple property filter
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1', { role: 'PRIMARY', status: 'ACTIVE' }) === true"));
    }

    @Test
    void testHasEdgeJavaScriptCallbackFunction() {
        // Callback function matching
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1', function(e) { return e.role === 'PRIMARY'; }) === true"));

        // Callback function mismatch
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1', function(e) { return e.role === 'BACKUP'; }) === false"));

        // Arrow function syntax
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasEdge('assignedTo', 'loc1', e => e.role === 'PRIMARY') === true"));
    }

    @Test
    void testHasIncomingEdgeJavaScript() {
        // 2-arg backward compat
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasIncomingEdge('assignedTo', 'u1@example.com') === true"));

        // 3-arg object filter
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasIncomingEdge('assignedTo', 'u1@example.com', { role: 'PRIMARY' }) === true"));

        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasIncomingEdge('assignedTo', 'u1@example.com', { role: 'NON_EXISTENT' }) === false"));
    }

    @Test
    void testHasAnyEdgeAndHasAllEdgesJavaScript() {
        // hasAnyEdge with filter
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasAnyEdge('assignedTo', ['loc1', 'loc2'], { role: 'PRIMARY' }) === true"));

        // hasAllEdges with filter
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasAllEdges('assignedTo', ['loc1'], { role: 'PRIMARY' }) === true"));

        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "hasAllEdges('assignedTo', ['loc1', 'loc2'], { role: 'PRIMARY' }) === false"));
    }

    @Test
    void testRelatedIdsJavaScript() {
        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "relatedIds('assignedTo').length === 2"));

        assertTrue(ruleContext.runScript(principalContext, resourceContext,
                "relatedIds('assignedTo', { role: 'PRIMARY' }).length === 1"
                + " && relatedIds('assignedTo', { role: 'PRIMARY' })[0] === 'loc1'"));
    }
}
