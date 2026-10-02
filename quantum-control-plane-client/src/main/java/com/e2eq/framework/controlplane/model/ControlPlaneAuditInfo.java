package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneAuditInfo {
    private Long creationTs;
    private String creationIdentity;
    private Long lastUpdateTs;
    private String lastUpdateIdentity;
    private String impersonatorSubject;
    private String impersonatorUserId;
    private String actingOnBehalfOfSubject;
    private String actingOnBehalfOfUserId;

    @JsonProperty("creationTs")
    public Long getCreationTs() { return creationTs; }

    @JsonProperty("creationTs")
    public void setCreationTs(Long creationTs) { this.creationTs = creationTs; }

    @JsonProperty("creationIdentity")
    public String getCreationIdentity() { return creationIdentity; }

    @JsonProperty("creationIdentity")
    public void setCreationIdentity(String creationIdentity) { this.creationIdentity = creationIdentity; }

    @JsonProperty("lastUpdateTs")
    public Long getLastUpdateTs() { return lastUpdateTs; }

    @JsonProperty("lastUpdateTs")
    public void setLastUpdateTs(Long lastUpdateTs) { this.lastUpdateTs = lastUpdateTs; }

    @JsonProperty("lastUpdateIdentity")
    public String getLastUpdateIdentity() { return lastUpdateIdentity; }

    @JsonProperty("lastUpdateIdentity")
    public void setLastUpdateIdentity(String lastUpdateIdentity) { this.lastUpdateIdentity = lastUpdateIdentity; }

    @JsonProperty("impersonatorSubject")
    public String getImpersonatorSubject() { return impersonatorSubject; }

    @JsonProperty("impersonatorSubject")
    public void setImpersonatorSubject(String impersonatorSubject) { this.impersonatorSubject = impersonatorSubject; }

    @JsonProperty("impersonatorUserId")
    public String getImpersonatorUserId() { return impersonatorUserId; }

    @JsonProperty("impersonatorUserId")
    public void setImpersonatorUserId(String impersonatorUserId) { this.impersonatorUserId = impersonatorUserId; }

    @JsonProperty("actingOnBehalfOfSubject")
    public String getActingOnBehalfOfSubject() { return actingOnBehalfOfSubject; }

    @JsonProperty("actingOnBehalfOfSubject")
    public void setActingOnBehalfOfSubject(String actingOnBehalfOfSubject) { this.actingOnBehalfOfSubject = actingOnBehalfOfSubject; }

    @JsonProperty("actingOnBehalfOfUserId")
    public String getActingOnBehalfOfUserId() { return actingOnBehalfOfUserId; }

    @JsonProperty("actingOnBehalfOfUserId")
    public void setActingOnBehalfOfUserId(String actingOnBehalfOfUserId) { this.actingOnBehalfOfUserId = actingOnBehalfOfUserId; }
}
