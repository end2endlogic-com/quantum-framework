package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.TestCustomerAccessResolver;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.query.filters.Filter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
 * Same as AccessListResolverRuleTest but runs with the RuleIndex enabled via TestProfile
 * to ensure behavior matches the default (list-scan) implementation.
 */
@QuarkusTest
@TestProfile(IndexEnabledProfile.class)
public class AccessListResolverRuleIndexTest extends BaseRepoTest {

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
    @TestSecurity(user = "alice@end2endlogic.com", roles = {"user"})
    public void testResolverBackedInFilter_withIndexEnabled_matchesDefault() {
        String realm = testUtils.getTestRealm();

        // Create a policy for identity "user" that attaches an IN filter using the resolver variable
        Policy p = new Policy();
        p.setRefName("resolver-policy-in-var");
        p.setDisplayName("Resolver Policy");
        p.setPrincipalId("user");
        p.setDataDomain(securityUtils.getSystemDataDomain());

        SecurityURIHeader hdr = new SecurityURIHeader.Builder()
                .withIdentity("user").withArea("sales").withFunctionalDomain("order").withAction("view").build();
        SecurityURIBody bdy = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*")
                .withTenantId("*").withOwnerId("*").withDataSegment("*")
                .withResourceId("*").build();

        Rule r = new Rule.Builder()
                .withName("user-sales-order-view-in")
                .withSecurityURI(new SecurityURI(hdr, bdy))
                // The variable below is provided by TestCustomerAccessResolver
                .withAndFilterString("customerId:^[${accessibleCustomerIds}]")
                .withEffect(RuleEffect.ALLOW)
                .withPriority(100)
                .withFinalRule(false)
                .build();
        p.getRules().add(r);

        policyRepo.save(realm, p);
        // reload which will build the index due to the TestProfile override
        ruleContext.reloadFromRepo(realm);

        // Build contexts that match the resolver's supports() and the rule
        PrincipalContext pc = new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withUserId("alice@end2endlogic.com")
                .withRoles(new String[]{"user"})
                .withDataDomain(new DataDomain("end2endlogic", "0000000001", "tenant-1", 0, "alice"))
                .withScope("AUTHENTICATED")
                .build();

        ResourceContext rc = new ResourceContext.Builder()
                .withArea("sales").withFunctionalDomain("order").withAction("view")
                .withOwnerId("alice").withResourceId("ORD-1").build();

        // Resolve filters using index-enabled RuleContext
        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), pc, rc, UserProfile.class);
        assertFalse(filters.isEmpty(), "Expected at least one filter from rule with resolver variable");

        // Find the $in filter on customerId and validate it contains the ObjectIds from the resolver
        Filter in = filters.stream().filter(f -> "$in".equals(f.getName()) && "customerId".equals(f.getField()))
                .findFirst().orElse(null);
        assertNotNull(in, "Expected an $in filter on customerId produced from the resolver list");
        assertTrue(in.getValue() instanceof List, "Filter value should be a List");
        @SuppressWarnings("unchecked")
        List<Object> vals = (List<Object>) in.getValue();
        assertEquals(2, vals.size(), "Expected two values from the resolver");
        assertTrue(vals.get(0) instanceof ObjectId, "Expected ObjectId elements (typed list)");

        // Order isn't strictly guaranteed; verify membership
        List<String> hexes = List.of(
                TestCustomerAccessResolver.ID1.toHexString(),
                TestCustomerAccessResolver.ID2.toHexString()
        );
        List<String> actual = vals.stream().map(v -> ((ObjectId) v).toHexString()).toList();
        assertTrue(actual.containsAll(hexes) && hexes.containsAll(actual), "$in list should match resolver-provided ObjectIds");
    }
}
