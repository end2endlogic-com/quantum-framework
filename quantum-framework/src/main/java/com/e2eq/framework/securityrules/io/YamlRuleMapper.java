package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.FilterJoinOp;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.securityrules.CompositeSecurityURIHeader;
import com.e2eq.framework.securityrules.RuleExpander;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility for converting YAML representations of rules into fully expanded {@link Rule} instances.
 * The conversion logic is shared between the CLI tooling and REST resources so that both
 * code paths interpret YAML payloads consistently.
 */
public final class YamlRuleMapper {

    private YamlRuleMapper() {
    }

    public static List<Rule> toRules(List<YamlRule> yamlRules) {
        if (yamlRules == null || yamlRules.isEmpty()) {
            return Collections.emptyList();
        }

        List<Rule> out = new ArrayList<>();
        for (YamlRule yr : yamlRules) {
            if (yr == null) {
                continue;
            }

            CompositeSecurityURIHeader ch = new CompositeSecurityURIHeader(
                    yr.identities, yr.areas, yr.functionalDomains, yr.actions);

            // Build placeholder header; actual values set during expansion
            SecurityURIHeader placeholderHeader = new SecurityURIHeader();
            SecurityURI uri = new SecurityURI(placeholderHeader,
                    yr.body != null ? yr.body.clone() : new com.e2eq.framework.model.securityrules.SecurityURIBody());

            Rule template = new Rule.Builder()
                    .withName(yr.name)
                    .withDescription(yr.description)
                    .withSecurityURI(uri)
                    .withPreconditionScript(yr.preconditionScript)
                    .withPostconditionScript(yr.postconditionScript)
                    .withEffect(parseEffect(yr.effect))
                    .withPriority(yr.priority != null ? yr.priority : Rule.DEFAULT_PRIORITY)
                    .withFinalRule(Boolean.TRUE.equals(yr.finalRule))
                    .withAndFilterString(yr.andFilterString)
                    .withOrFilterString(yr.orFilterString)
                    .withJoinOp(parseJoinOp(yr.joinOp))
                    .build();

            out.addAll(RuleExpander.expand(template, ch));
        }
        return out;
    }

    private static RuleEffect parseEffect(String s) {
        if (s == null || s.isBlank()) {
            return RuleEffect.DENY; // default
        }
        return RuleEffect.valueOf(s.trim().toUpperCase());
    }

    private static FilterJoinOp parseJoinOp(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return FilterJoinOp.valueOf(s.trim().toUpperCase());
    }
}
