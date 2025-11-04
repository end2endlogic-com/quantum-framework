package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.model.securityrules.RuleEffect;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class YamlRuleLoaderIT {

    private final YamlRuleLoader loader = new YamlRuleLoader();

    @Test
    void loads_scalar_headers_as_singleton_lists_and_expands_to_one_rule() throws Exception {
        try (InputStream is = resource("/rules_scalar.yaml")) {
            List<Rule> rules = loader.load(is);
            assertNotNull(rules, "rules should not be null");
            assertEquals(1, rules.size(), "Expected exactly one expanded rule");

            Rule r = rules.get(0);
            assertEquals("allow-singleton", r.getName());
            assertEquals(RuleEffect.ALLOW, r.getEffect());
            assertEquals(7, r.getPriority());
            assertTrue(r.isFinalRule());
            assertEquals("status:OPEN", r.getAndFilterString());
            assertEquals("ownerId:system@system.com", r.getOrFilterString());
            assertNotNull(r.getJoinOp());

            SecurityURI uri = r.getSecurityURI();
            SecurityURIHeader h = uri.getHeader();
            assertEquals("admin", h.getIdentity());
            assertEquals("sales", h.getArea());
            assertEquals("order", h.getFunctionalDomain());
            assertEquals("read", h.getAction());
        }
    }

    @Test
    void loads_list_headers_and_expands_cartesian_product() throws Exception {
        try (InputStream is = resource("/rules_lists.yaml")) {
            List<Rule> rules = loader.load(is);
            assertNotNull(rules);
            // identities(2) x areas(1) x domains(2) x actions(2) = 8
            assertEquals(8, rules.size(), "Expected 8 expanded rules");

            Set<String> headers = rules.stream()
                    .map(r -> r.getSecurityURI().getHeader().getURIString())
                    .collect(Collectors.toSet());

            // expected combinations (all lower-cased)
            String[] ids = {"admin", "manager"};
            String[] areas = {"sales"};
            String[] domains = {"order", "invoice"};
            String[] actions = {"read", "write"};

            for (String id : ids) {
                for (String area : areas) {
                    for (String dom : domains) {
                        for (String act : actions) {
                            String expected = id + ":" + area + ":" + dom + ":" + act;
                            assertTrue(headers.contains(expected), "Missing header combination: " + expected);
                        }
                    }
                }
            }

            // Verify meta carried over from template
            rules.forEach(r -> {
                assertEquals("multi-combo", r.getName());
                assertEquals(RuleEffect.ALLOW, r.getEffect());
                assertEquals(3, r.getPriority());
                assertFalse(r.isFinalRule());
            });
        }
    }

    @Test
    void missing_axes_are_normalized_to_wildcards() throws Exception {
        try (InputStream is = resource("/rules_missing.yaml")) {
            List<Rule> rules = loader.load(is);
            assertNotNull(rules);
            assertEquals(1, rules.size());

            SecurityURIHeader h = rules.get(0).getSecurityURI().getHeader();
            assertEquals("*", h.getIdentity());
            assertEquals("*", h.getArea());
            assertEquals("*", h.getFunctionalDomain());
            assertEquals("read", h.getAction());
            assertEquals(RuleEffect.ALLOW, rules.get(0).getEffect());
        }
    }

    private static InputStream resource(String path) {
        InputStream is = YamlRuleLoaderIT.class.getResourceAsStream(path);
        assertNotNull(is, "Resource not found: " + path);
        return is;
    }
}
