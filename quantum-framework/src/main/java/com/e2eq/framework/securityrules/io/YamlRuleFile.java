package com.e2eq.framework.securityrules.io;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlRuleFile {
    @JsonProperty("rules")
    public List<YamlRule> rules;
}
