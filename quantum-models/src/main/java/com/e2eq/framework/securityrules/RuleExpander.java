package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;

import java.util.ArrayList;
import java.util.List;

/**
 * Expands a single logical rule with list-valued header parts into concrete rules
 * with single-valued headers reusing existing wildcard/matching logic.
 */
public final class RuleExpander {

    private RuleExpander() {}

    public static List<Rule> expand(Rule templateRule, CompositeSecurityURIHeader compHeader) {
        if (templateRule == null || compHeader == null) return List.of();

        List<String> identities = normalize(compHeader.getIdentities());
        List<String> areas = normalize(compHeader.getAreas());
        List<String> domains = normalize(compHeader.getFunctionalDomains());
        List<String> actions = normalize(compHeader.getActions());

        int capacity = Math.max(1, identities.size() * areas.size() * domains.size() * actions.size());
        List<Rule> out = new ArrayList<>(capacity);

        for (String identity : identities) {
            for (String area : areas) {
                for (String domain : domains) {
                    for (String action : actions) {
                        Rule cloned = cloneRuleWithHeader(templateRule, identity, area, domain, action);
                        out.add(cloned);
                    }
                }
            }
        }
        return out;
    }

    private static List<String> normalize(List<String> in) {
        if (in == null || in.isEmpty()) return List.of("*");
        return in;
    }

    private static Rule cloneRuleWithHeader(Rule src, String identity, String area, String domain, String action) {
        SecurityURI srcUri = src.getSecurityURI();
        SecurityURIHeader newHeader = new SecurityURIHeader.Builder()
                .withIdentity(identity)
                .withArea(area)
                .withFunctionalDomain(domain)
                .withAction(action)
                .build();

        SecurityURI newUri = new SecurityURI(newHeader, srcUri.getBody().clone());

        return new Rule.Builder()
                .withName(src.getName())
                .withDescription(src.getDescription())
                .withSecurityURI(newUri)
                .withPreconditionScript(src.getPreconditionScript())
                .withPostconditionScript(src.getPostconditionScript())
                .withEffect(src.getEffect())
                .withPriority(src.getPriority())
                .withFinalRule(src.isFinalRule())
                .withAndFilterString(src.getAndFilterString())
                .withOrFilterString(src.getOrFilterString())
                .withJoinOp(src.getJoinOp())
                .build();
    }
}
