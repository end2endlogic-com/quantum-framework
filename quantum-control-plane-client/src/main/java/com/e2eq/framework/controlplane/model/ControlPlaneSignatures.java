package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneSignatures {
    private String referencesSignature;
    private String auditInfoSignature;
    private String entityHash;

    @JsonProperty("referencesSignature")
    public String getReferencesSignature() { return referencesSignature; }

    @JsonProperty("referencesSignature")
    public void setReferencesSignature(String referencesSignature) { this.referencesSignature = referencesSignature; }

    @JsonProperty("auditInfoSignature")
    public String getAuditInfoSignature() { return auditInfoSignature; }

    @JsonProperty("auditInfoSignature")
    public void setAuditInfoSignature(String auditInfoSignature) { this.auditInfoSignature = auditInfoSignature; }

    @JsonProperty("entityHash")
    public String getEntityHash() { return entityHash; }

    @JsonProperty("entityHash")
    public void setEntityHash(String entityHash) { this.entityHash = entityHash; }
}
