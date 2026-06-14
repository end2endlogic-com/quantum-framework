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
    private String emailDomain;
    private String tenantId;
    private String orgRefName;
    private String accountNumber;
    private String connectionString;
    private Boolean active;

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

    @JsonProperty("emailDomain")
    public String getEmailDomain() { return emailDomain; }

    @JsonProperty("emailDomain")
    public void setEmailDomain(String emailDomain) { this.emailDomain = emailDomain; }

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
}
