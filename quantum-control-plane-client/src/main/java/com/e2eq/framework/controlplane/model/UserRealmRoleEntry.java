package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

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
    private String id;
    private String refName;
    private String displayName;
    private ControlPlaneDataDomain dataDomain;
    private ControlPlaneActiveStatus activeStatus;
    private List<String> tags;
    private List<ControlPlaneTag> advancedTags;
    private ControlPlaneAuditInfo auditInfo;
    private List<ControlPlaneReferenceEntry> references;
    private List<ControlPlanePersistentEvent> persistentEvents;
    private ControlPlaneSignatures signatures;
    private Map<String, Object> unmappedProperties;
    private Long version;
    private String subject;
    private String authorizedTenantRegEx;

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

    @JsonProperty("id")
    public String getId() { return id; }

    @JsonProperty("id")
    public void setId(String id) { this.id = id; }

    @JsonProperty("refName")
    public String getRefName() { return refName; }

    @JsonProperty("refName")
    public void setRefName(String refName) { this.refName = refName; }

    @JsonProperty("displayName")
    public String getDisplayName() { return displayName; }

    @JsonProperty("displayName")
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    @JsonProperty("dataDomain")
    public ControlPlaneDataDomain getDataDomain() { return dataDomain; }

    @JsonProperty("dataDomain")
    public void setDataDomain(ControlPlaneDataDomain dataDomain) { this.dataDomain = dataDomain; }

    @JsonProperty("activeStatus")
    public ControlPlaneActiveStatus getActiveStatus() { return activeStatus; }

    @JsonProperty("activeStatus")
    public void setActiveStatus(ControlPlaneActiveStatus activeStatus) { this.activeStatus = activeStatus; }

    @JsonProperty("tags")
    public List<String> getTags() { return tags; }

    @JsonProperty("tags")
    public void setTags(List<String> tags) { this.tags = tags; }

    @JsonProperty("advancedTags")
    public List<ControlPlaneTag> getAdvancedTags() { return advancedTags; }

    @JsonProperty("advancedTags")
    public void setAdvancedTags(List<ControlPlaneTag> advancedTags) { this.advancedTags = advancedTags; }

    @JsonProperty("auditInfo")
    public ControlPlaneAuditInfo getAuditInfo() { return auditInfo; }

    @JsonProperty("auditInfo")
    public void setAuditInfo(ControlPlaneAuditInfo auditInfo) { this.auditInfo = auditInfo; }

    @JsonProperty("references")
    public List<ControlPlaneReferenceEntry> getReferences() { return references; }

    @JsonProperty("references")
    public void setReferences(List<ControlPlaneReferenceEntry> references) { this.references = references; }

    @JsonProperty("persistentEvents")
    public List<ControlPlanePersistentEvent> getPersistentEvents() { return persistentEvents; }

    @JsonProperty("persistentEvents")
    public void setPersistentEvents(List<ControlPlanePersistentEvent> persistentEvents) { this.persistentEvents = persistentEvents; }

    @JsonProperty("signatures")
    public ControlPlaneSignatures getSignatures() { return signatures; }

    @JsonProperty("signatures")
    public void setSignatures(ControlPlaneSignatures signatures) { this.signatures = signatures; }

    @JsonProperty("unmappedProperties")
    public Map<String, Object> getUnmappedProperties() { return unmappedProperties; }

    @JsonProperty("unmappedProperties")
    public void setUnmappedProperties(Map<String, Object> unmappedProperties) { this.unmappedProperties = unmappedProperties; }

    @JsonProperty("version")
    public Long getVersion() { return version; }

    @JsonProperty("version")
    public void setVersion(Long version) { this.version = version; }

    @JsonProperty("subject")
    public String getSubject() { return subject; }

    @JsonProperty("subject")
    public void setSubject(String subject) { this.subject = subject; }

    @JsonProperty("authorizedTenantRegEx")
    public String getAuthorizedTenantRegEx() { return authorizedTenantRegEx; }

    @JsonProperty("authorizedTenantRegEx")
    public void setAuthorizedTenantRegEx(String authorizedTenantRegEx) { this.authorizedTenantRegEx = authorizedTenantRegEx; }
}
