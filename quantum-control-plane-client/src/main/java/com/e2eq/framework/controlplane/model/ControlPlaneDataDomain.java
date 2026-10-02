package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneDataDomain {
    @NotNull
    private String orgRefName;
    @NotNull
    private String accountNum;
    @NotNull
    private String tenantId;
    @NotNull
    private Integer dataSegment;
    @NotNull
    private String ownerId;
    private String businessTransactionId;
    private String locationId;

    @JsonProperty("orgRefName")
    public String getOrgRefName() { return orgRefName; }

    @JsonProperty("orgRefName")
    public void setOrgRefName(String orgRefName) { this.orgRefName = orgRefName; }

    @JsonProperty("accountNum")
    public String getAccountNum() { return accountNum; }

    @JsonProperty("accountNum")
    public void setAccountNum(String accountNum) { this.accountNum = accountNum; }

    @JsonProperty("tenantId")
    public String getTenantId() { return tenantId; }

    @JsonProperty("tenantId")
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @JsonProperty("dataSegment")
    public Integer getDataSegment() { return dataSegment; }

    @JsonProperty("dataSegment")
    public void setDataSegment(Integer dataSegment) { this.dataSegment = dataSegment; }

    @JsonProperty("ownerId")
    public String getOwnerId() { return ownerId; }

    @JsonProperty("ownerId")
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    @JsonProperty("businessTransactionId")
    public String getBusinessTransactionId() { return businessTransactionId; }

    @JsonProperty("businessTransactionId")
    public void setBusinessTransactionId(String businessTransactionId) { this.businessTransactionId = businessTransactionId; }

    @JsonProperty("locationId")
    public String getLocationId() { return locationId; }

    @JsonProperty("locationId")
    public void setLocationId(String locationId) { this.locationId = locationId; }
}
