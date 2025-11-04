package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents one rule as written in YAML. All header axes are lists, but can
 * be provided as a single scalar in YAML thanks to @JsonFormat.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YamlRule {

    // Header axes (accept single value as array)
    @JsonProperty("identities")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> identities;

    @JsonProperty("areas")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> areas;

    @JsonProperty("functionalDomains")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> functionalDomains;

    @JsonProperty("actions")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> actions;

    // Non-header parts of the rule
    public String name;
    public String description;
    public String preconditionScript;
    public String postconditionScript;
    public String andFilterString;
    public String orFilterString;
    public String joinOp; // maps to FilterJoinOp
    public String effect; // maps to RuleEffect
    public Integer priority; // default if null
    public Boolean finalRule; // default if null

    // Body of the security URI (whatever your SecurityURIBody needs)
    @JsonProperty("body")
    public SecurityURIBody body;
}
