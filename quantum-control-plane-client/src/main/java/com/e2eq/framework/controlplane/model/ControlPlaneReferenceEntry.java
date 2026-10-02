package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneReferenceEntry {
    @NotNull
    private String referencedId;
    @NotNull
    private String type;
    private String refName;

    @JsonProperty("referencedId")
    public String getReferencedId() { return referencedId; }

    @JsonProperty("referencedId")
    public void setReferencedId(String referencedId) { this.referencedId = referencedId; }

    @JsonProperty("type")
    public String getType() { return type; }

    @JsonProperty("type")
    public void setType(String type) { this.type = type; }

    @JsonProperty("refName")
    public String getRefName() { return refName; }

    @JsonProperty("refName")
    public void setRefName(String refName) { this.refName = refName; }
}
