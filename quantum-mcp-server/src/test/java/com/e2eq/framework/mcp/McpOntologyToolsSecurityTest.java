package com.e2eq.framework.mcp;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class McpOntologyToolsSecurityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void relationshipQueryRejectsRealmDifferentFromAuthenticatedRealm() {
        SecurityContext.setPrincipalContext(principal("tenant-a"));

        String result = new McpOntologyTools()
                .query_relationships("entity-1", "both", null, "tenant-b");

        Assertions.assertTrue(result.contains("RelationshipQueryFailed"));
        Assertions.assertTrue(result.contains("does not match authenticated effective realm"));
    }

    @Test
    void predicateQueryRejectsRealmDifferentFromAuthenticatedRealm() {
        SecurityContext.setPrincipalContext(principal("tenant-a"));

        String result = new McpOntologyTools()
                .query_predicates(null, null, "tenant-b");

        Assertions.assertTrue(result.contains("PredicateQueryFailed"));
        Assertions.assertTrue(result.contains("does not match authenticated effective realm"));
    }

    @Test
    void explicitRealmCannotReplaceMissingPrincipalContext() {
        String result = new McpOntologyTools()
                .query_relationships("entity-1", "both", null, "tenant-a");

        Assertions.assertTrue(result.contains("RelationshipQueryFailed"));
        Assertions.assertTrue(result.contains("Authenticated principal context is required"));
    }

    private PrincipalContext principal(String realm) {
        return new PrincipalContext.Builder()
                .withUserId("user@tenant-a")
                .withDefaultRealm(realm)
                .withDataDomain(new DataDomain("tenant-a", "0000000000", "tenant.a", 0, "user@tenant-a"))
                .withRoles(new String[] { "user" })
                .withScope("AUTHENTICATED")
                .build();
    }
}
