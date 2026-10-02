package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneTag {
    private String category;
    private String tagDisplayName;
    private List<String> additionalData;
    private List<ControlPlaneStringNameValue> attributes;
    private Boolean visible;

    @JsonProperty("category")
    public String getCategory() { return category; }

    @JsonProperty("category")
    public void setCategory(String category) { this.category = category; }

    @JsonProperty("tagDisplayName")
    public String getTagDisplayName() { return tagDisplayName; }

    @JsonProperty("tagDisplayName")
    public void setTagDisplayName(String tagDisplayName) { this.tagDisplayName = tagDisplayName; }

    @JsonProperty("additionalData")
    public List<String> getAdditionalData() { return additionalData; }

    @JsonProperty("additionalData")
    public void setAdditionalData(List<String> additionalData) { this.additionalData = additionalData; }

    @JsonProperty("attributes")
    public List<ControlPlaneStringNameValue> getAttributes() { return attributes; }

    @JsonProperty("attributes")
    public void setAttributes(List<ControlPlaneStringNameValue> attributes) { this.attributes = attributes; }

    @JsonProperty("visible")
    public Boolean getVisible() { return visible; }

    @JsonProperty("visible")
    public void setVisible(Boolean visible) { this.visible = visible; }
}
