package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRealmRoleEntry {
    @NotNull
    private String userId;
    @NotNull
    private String realmRefName;
    @NotNull
    private List<String> roles;
    private List<String> authorizedApplications;
    private String defaultApplication;
    private List<String> authorizedTenantIds;
    private String sponsoringOrgRefName;
    private String status;

    @JsonProperty("userId")
    public String getUserId() { return userId; }

    @JsonProperty("userId")
    public void setUserId(String userId) { this.userId = userId; }

    @JsonProperty("realmRefName")
    public String getRealmRefName() { return realmRefName; }

    @JsonProperty("realmRefName")
    public void setRealmRefName(String realmRefName) { this.realmRefName = realmRefName; }

    @JsonProperty("roles")
    public List<String> getRoles() { return roles; }

    @JsonProperty("roles")
    public void setRoles(List<String> roles) { this.roles = roles; }

    @JsonProperty("authorizedApplications")
    public List<String> getAuthorizedApplications() { return authorizedApplications; }

    @JsonProperty("authorizedApplications")
    public void setAuthorizedApplications(List<String> authorizedApplications) { this.authorizedApplications = authorizedApplications; }

    @JsonProperty("defaultApplication")
    public String getDefaultApplication() { return defaultApplication; }

    @JsonProperty("defaultApplication")
    public void setDefaultApplication(String defaultApplication) { this.defaultApplication = defaultApplication; }

    @JsonProperty("authorizedTenantIds")
    public List<String> getAuthorizedTenantIds() { return authorizedTenantIds; }

    @JsonProperty("authorizedTenantIds")
    public void setAuthorizedTenantIds(List<String> authorizedTenantIds) { this.authorizedTenantIds = authorizedTenantIds; }

    @JsonProperty("sponsoringOrgRefName")
    public String getSponsoringOrgRefName() { return sponsoringOrgRefName; }

    @JsonProperty("sponsoringOrgRefName")
    public void setSponsoringOrgRefName(String sponsoringOrgRefName) { this.sponsoringOrgRefName = sponsoringOrgRefName; }

    @JsonProperty("status")
    public String getStatus() { return status; }

    @JsonProperty("status")
    public void setStatus(String status) { this.status = status; }
}
