package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.securityrules.FilterJoinOp;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.securityrules.CompositeSecurityURIHeader;
import com.e2eq.framework.securityrules.RuleExpander;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class YamlRuleLoader {

    private final ObjectMapper mapper;

    public YamlRuleLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    public List<Rule> load(Path yamlPath) throws IOException {
        if (yamlPath == null || !Files.exists(yamlPath)) return Collections.emptyList();
        try (InputStream is = Files.newInputStream(yamlPath)) {
            YamlRuleFile file = mapper.readValue(is, YamlRuleFile.class);
            return toRules(file);
        }
    }

    public List<Rule> load(InputStream yamlStream) throws IOException {
        if (yamlStream == null) return Collections.emptyList();
        YamlRuleFile file = mapper.readValue(yamlStream, YamlRuleFile.class);
        return toRules(file);
    }

    private List<Rule> toRules(YamlRuleFile file) {
        if (file == null || file.rules == null || file.rules.isEmpty()) return Collections.emptyList();

        List<Rule> out = new ArrayList<>();
        for (YamlRule yr : file.rules) {
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
        if (s == null || s.isBlank()) return RuleEffect.DENY; // default
        return RuleEffect.valueOf(s.trim().toUpperCase());
    }

    private static FilterJoinOp parseJoinOp(String s) {
        if (s == null || s.isBlank()) return null;
        return FilterJoinOp.valueOf(s.trim().toUpperCase());
    }
}
