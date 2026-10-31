package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneStringNameValue {
    private String name;
    private String value;

    @JsonProperty("name")
    public String getName() { return name; }

    @JsonProperty("name")
    public void setName(String name) { this.name = name; }

    @JsonProperty("value")
    public String getValue() { return value; }

    @JsonProperty("value")
    public void setValue(String value) { this.value = value; }
}
