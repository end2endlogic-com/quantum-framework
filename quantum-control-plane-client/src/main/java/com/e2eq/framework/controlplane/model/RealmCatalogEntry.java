package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RealmCatalogEntry {
    @NotNull
    private String refName;
    private String displayName;
    private String databaseName;
    private String deploymentType;
    private String tenancyMode;
    private String emailDomain;
    private String defaultAdminUserId;
    private Integer dataSegment;
    private String tenantId;
    private String orgRefName;
    private String accountNumber;
    private String connectionString;
    private Boolean active;
    private String applicationRefName;

    @JsonProperty("refName")
    public String getRefName() { return refName; }

    @JsonProperty("refName")
    public void setRefName(String refName) { this.refName = refName; }

    @JsonProperty("displayName")
    public String getDisplayName() { return displayName; }

    @JsonProperty("displayName")
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    @JsonProperty("databaseName")
    public String getDatabaseName() { return databaseName; }

    @JsonProperty("databaseName")
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    @JsonProperty("deploymentType")
    public String getDeploymentType() { return deploymentType; }

    @JsonProperty("deploymentType")
    public void setDeploymentType(String deploymentType) { this.deploymentType = deploymentType; }

    @JsonProperty("tenancyMode")
    public String getTenancyMode() { return tenancyMode; }

    @JsonProperty("tenancyMode")
    public void setTenancyMode(String tenancyMode) { this.tenancyMode = tenancyMode; }

    @JsonProperty("emailDomain")
    public String getEmailDomain() { return emailDomain; }

    @JsonProperty("emailDomain")
    public void setEmailDomain(String emailDomain) { this.emailDomain = emailDomain; }

    @JsonProperty("defaultAdminUserId")
    public String getDefaultAdminUserId() { return defaultAdminUserId; }

    @JsonProperty("defaultAdminUserId")
    public void setDefaultAdminUserId(String defaultAdminUserId) { this.defaultAdminUserId = defaultAdminUserId; }

    @JsonProperty("dataSegment")
    public Integer getDataSegment() { return dataSegment; }

    @JsonProperty("dataSegment")
    public void setDataSegment(Integer dataSegment) { this.dataSegment = dataSegment; }

    @JsonProperty("tenantId")
    public String getTenantId() { return tenantId; }

    @JsonProperty("tenantId")
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @JsonProperty("orgRefName")
    public String getOrgRefName() { return orgRefName; }

    @JsonProperty("orgRefName")
    public void setOrgRefName(String orgRefName) { this.orgRefName = orgRefName; }

    @JsonProperty("accountNumber")
    public String getAccountNumber() { return accountNumber; }

    @JsonProperty("accountNumber")
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    @JsonProperty("connectionString")
    public String getConnectionString() { return connectionString; }

    @JsonProperty("connectionString")
    public void setConnectionString(String connectionString) { this.connectionString = connectionString; }

    @JsonProperty("active")
    public Boolean getActive() { return active; }

    @JsonProperty("active")
    public void setActive(Boolean active) { this.active = active; }

    @JsonProperty("applicationRefName")
    public String getApplicationRefName() { return applicationRefName; }

    @JsonProperty("applicationRefName")
    public void setApplicationRefName(String applicationRefName) { this.applicationRefName = applicationRefName; }
}
