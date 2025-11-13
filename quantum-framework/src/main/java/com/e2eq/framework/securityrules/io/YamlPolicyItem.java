package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.security.Rule;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlPolicyItem {

    public String refName;
    public String displayName;
    public String description;

    public String principalId;

    /** principalType maps to {@link com.e2eq.framework.model.security.Policy.PrincipalType}. */
    public String principalType;

    @JsonProperty("rules")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<YamlRule> rules;

    @JsonIgnore
    public List<Rule> legacyRules;
}
