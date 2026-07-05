package com.e2eq.framework.security;

import com.e2eq.framework.grammar.sql.QueryToSqlListener;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.security.runtime.RuleContext;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link RuleContext#getGovernedFilterString} — the store-agnostic governance seam that
 * lowers matched policy rules into a BIAPIQuery grammar STRING for federated (SQL/REST) sources,
 * end-to-end through {@link QueryToSqlListener}. Covers: user-query + policy composition, the
 * fail-closed contract (unlike getFilters, an unresolvable variable throws), non-ALLOW denial,
 * and parity with getFilters on the same matched rule set.
 */
public class GovernedFilterStringTest {

    RuleContext ruleContext;
    PrincipalContext principal;

    /** Dummy model so the parity call to the Morphia-coupled getFilters() has a class to key on. */
    static class LocationModel extends UnversionedBaseModel {
        @Override public String bmFunctionalDomain() { return "locations"; }
        @Override public String bmFunctionalArea() { return "location_hub"; }
    }

    private ResourceContext rc() {
        return new ResourceContext.Builder()
                .withArea("location_hub").withFunctionalDomain("locations").withAction("list")
                .withResourceId("res-1").withOwnerId(principal.getUserId()).build();
    }

    @BeforeEach
    void setUp() {
        ruleContext = new RuleContext();
        ruleContext.clear();
        // orgRefName="acme" so ${orgRefName} resolves to a clean string token.
        DataDomain dd = new DataDomain("acme", "0000000001", "acmeTenant", 0, "user@test.com");
        principal = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm").withDataDomain(dd)
                .withUserId("user@test.com").withRoles(new String[]{"user"}).build();
    }

    @Test
    void composesUserQueryWithTenantRule_andTranslatesToGovernedSql() {
        ruleContext.addRule(header(), createRule("org-scope", "org:${orgRefName}", null, RuleEffect.ALLOW, 10));

        Optional<String> governed = ruleContext.getGovernedFilterString("status:active", principal, rc());
        assertTrue(governed.isPresent());
        assertEquals("(status:active) && (org:acme)", governed.get());

        // End-to-end: the governed grammar string translates to governed SQL with BOTH predicates.
        QueryToSqlListener.SqlWhere w = QueryToSqlListener.translate(governed.get());
        assertTrue(w.whereClause.contains("org = ?"), "governed SQL must carry the policy predicate: " + w.whereClause);
        assertTrue(w.whereClause.contains("status = ?"), "governed SQL must carry the user predicate: " + w.whereClause);
        assertTrue(w.params.contains("acme") && w.params.contains("active"), "params: " + w.params);
    }

    @Test
    void policyOnly_whenNoUserQuery() {
        ruleContext.addRule(header(), createRule("org-scope", "org:${orgRefName}", null, RuleEffect.ALLOW, 10));
        Optional<String> governed = ruleContext.getGovernedFilterString(null, principal, rc());
        assertEquals("(org:acme)", governed.orElseThrow());
    }

    @Test
    void userOrCannotEscapePolicyAnd() {
        // The governance-critical shape: a user OR must be gated by the policy AND across BOTH branches.
        ruleContext.addRule(header(), createRule("org-scope", "org:${orgRefName}", null, RuleEffect.ALLOW, 10));
        String governed = ruleContext.getGovernedFilterString("a:1 || b:2", principal, rc()).orElseThrow();
        assertEquals("(a:1 || b:2) && (org:acme)", governed);

        QueryToSqlListener.SqlWhere w = QueryToSqlListener.translate(governed);
        // org must AND with the WHOLE (b OR a) group, never a single branch.
        assertEquals("(org = ? AND (b = ? OR a = ?))", w.whereClause);
    }

    @Test
    void failsClosed_onUnresolvableVariable() {
        // getFilters() SKIPS this rule; the federated seam must NOT — dropping a policy filter leaks rows.
        ruleContext.addRule(header(), createRule("bad-var", "id:^[${unknownVariable}]", null, RuleEffect.ALLOW, 10));
        assertThrows(SecurityException.class,
                () -> ruleContext.getGovernedFilterString("status:active", principal, rc()));
    }

    @Test
    void failsClosed_whenNotAllowed() {
        ruleContext.addRule(header(), createRule("deny", "org:${orgRefName}", null, RuleEffect.DENY, 10));
        assertThrows(SecurityException.class,
                () -> ruleContext.getGovernedFilterString("status:active", principal, rc()));
    }

    @Test
    void parityWithGetFilters_sameMatchedRuleSet() {
        ruleContext.addRule(header(), createRule("org-scope", "org:${orgRefName}", null, RuleEffect.ALLOW, 10));
        // getFilters sees exactly one matched policy filter for this resource...
        List<Filter> filters = ruleContext.getFilters(new ArrayList<>(), principal, rc(), LocationModel.class);
        assertEquals(1, filters.size());
        // ...and the governed string carries that same single policy predicate.
        String governed = ruleContext.getGovernedFilterString(null, principal, rc()).orElseThrow();
        assertEquals("(org:acme)", governed);
    }

    // --- helpers -----------------------------------------------------------------

    private SecurityURIHeader header() {
        return new SecurityURIHeader("user", "location_hub", "locations", "list");
    }

    private Rule createRule(String name, String andFilterString, String orFilterString,
                            RuleEffect effect, int priority) {
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*").withAccountNumber("*").withRealm("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        SecurityURI uri = new SecurityURI(header(), body);
        return new Rule.Builder()
                .withName(name).withSecurityURI(uri).withEffect(effect).withPriority(priority)
                .withAndFilterString(andFilterString).withOrFilterString(orFilterString).build();
    }
}
