package com.e2eq.framework.security;

import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.TestStringAccessResolver;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.query.filters.Filter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test illustrating that a resolver can publish StringLiteral values
 * which remain strings (no ObjectId coercion) when used inside an IN clause.
 */
@QuarkusTest
public class AccessListResolverStringLiteralTest extends BaseRepoTest {

    @Inject
    RuleContext ruleContext;

    @Inject
    PolicyRepo policyRepo;

    @Inject
    SecurityUtils securityUtils;

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
    }

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "bob@end2endlogic.com", roles = {"user"})
    public void testResolverBackedInFilter_StringLiterals() {
        String realm = testUtils.getTestRealm();

        // Create a policy for identity "user" that attaches an IN filter using the string-literal resolver variable
        Policy p = new Policy();
        p.setRefName("resolver-policy-in-var-strings");
        p.setDisplayName("Resolver Policy (Strings)");
        p.setPrincipalId("user");
        p.setDataDomain(securityUtils.getSystemDataDomain());

        SecurityURIHeader hdr = new SecurityURIHeader.Builder()
                .withIdentity("user").withArea("sales").withFunctionalDomain("order").withAction("view").build();
        SecurityURIBody bdy = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*")
                .withResourceId("*").build();

        Rule r = new Rule.Builder()
                .withName("user-sales-order-view-in-strings")
                .withSecurityURI(new SecurityURI(hdr, bdy))
                // The variable below is provided by TestStringAccessResolver
                .withAndFilterString("customerCode:^[${accessibleCustomerCodes}]")
                .withEffect(RuleEffect.ALLOW)
                .withPriority(100)
                .withFinalRule(false)
                .build();
        p.getRules().add(r);

        policyRepo.save(realm, p);
        ruleContext.reloadFromRepo(realm);

        // Build contexts that match the resolver's supports() and the rule
        PrincipalContext pc = new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withUserId("bob@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .withDataDomain(new com.e2eq.framework.model.persistent.base.DataDomain("end2endlogic", "0000000001", "tenant-1", 0, "bob"))
                .withScope("AUTHENTICATED")
                .build();

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("sales").withFunctionalDomain("order").withAction("view")
                .withOwnerId("bob").withResourceId("ORD-2").build();

        // Resolve filters
        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), pc, rc, UserProfile.class);
        assertFalse(filters.isEmpty(), "Expected at least one filter from rule with resolver variable");

        // Find the $in filter on customerCode and validate it contains the raw strings from the resolver
        Filter in = filters.stream().filter(f -> "$in".equals(f.getName()) && "customerCode".equals(f.getField()))
                .findFirst().orElse(null);
        assertNotNull(in, "Expected an $in filter on customerCode produced from the resolver list");
        assertTrue(in.getValue() instanceof List, "Filter value should be a List");
        @SuppressWarnings("unchecked")
        List<Object> vals = (List<Object>) in.getValue();
        assertEquals(2, vals.size(), "Expected two values from the resolver");

        // Ensure neither element was coerced to ObjectId and that values match exactly
        for (Object v : vals) {
            assertFalse(v instanceof ObjectId, "StringLiteral elements must not be coerced to ObjectId");
            assertTrue(v instanceof String, "Expected plain String elements");
        }
        List<String> actual = vals.stream().map(Object::toString).toList();
        assertTrue(actual.contains(TestStringAccessResolver.HEX_LIKE), "Hex-like value should remain a String");
        assertTrue(actual.contains(TestStringAccessResolver.CODE_2), "Expected CODE_2 string present");
    }
}
