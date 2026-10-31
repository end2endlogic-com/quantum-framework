package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RealmMembershipEntry {
    @NotNull
    private String realmRefName;
    @NotNull
    private String organizationRefName;
    private String accountId;
    private String tenantId;
    @NotNull
    private String membershipRole;
    private String participationStatus;
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
    private String realmDisplayName;
    private String realmEmailDomain;
    private String defaultAdminUserId;
    private String realmEditionRefName;
    private String provisioningMode;
    private String setupStatus;
    private Integer setupCompletionPercent;

    @JsonProperty("realmRefName")
    public String getRealmRefName() { return realmRefName; }

    @JsonProperty("realmRefName")
    public void setRealmRefName(String realmRefName) { this.realmRefName = realmRefName; }

    @JsonProperty("organizationRefName")
    public String getOrganizationRefName() { return organizationRefName; }

    @JsonProperty("organizationRefName")
    public void setOrganizationRefName(String organizationRefName) { this.organizationRefName = organizationRefName; }

    @JsonProperty("accountId")
    public String getAccountId() { return accountId; }

    @JsonProperty("accountId")
    public void setAccountId(String accountId) { this.accountId = accountId; }

    @JsonProperty("tenantId")
    public String getTenantId() { return tenantId; }

    @JsonProperty("tenantId")
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @JsonProperty("membershipRole")
    public String getMembershipRole() { return membershipRole; }

    @JsonProperty("membershipRole")
    public void setMembershipRole(String membershipRole) { this.membershipRole = membershipRole; }

    @JsonProperty("participationStatus")
    public String getParticipationStatus() { return participationStatus; }

    @JsonProperty("participationStatus")
    public void setParticipationStatus(String participationStatus) { this.participationStatus = participationStatus; }

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

    @JsonProperty("realmDisplayName")
    public String getRealmDisplayName() { return realmDisplayName; }

    @JsonProperty("realmDisplayName")
    public void setRealmDisplayName(String realmDisplayName) { this.realmDisplayName = realmDisplayName; }

    @JsonProperty("realmEmailDomain")
    public String getRealmEmailDomain() { return realmEmailDomain; }

    @JsonProperty("realmEmailDomain")
    public void setRealmEmailDomain(String realmEmailDomain) { this.realmEmailDomain = realmEmailDomain; }

    @JsonProperty("defaultAdminUserId")
    public String getDefaultAdminUserId() { return defaultAdminUserId; }

    @JsonProperty("defaultAdminUserId")
    public void setDefaultAdminUserId(String defaultAdminUserId) { this.defaultAdminUserId = defaultAdminUserId; }

    @JsonProperty("realmEditionRefName")
    public String getRealmEditionRefName() { return realmEditionRefName; }

    @JsonProperty("realmEditionRefName")
    public void setRealmEditionRefName(String realmEditionRefName) { this.realmEditionRefName = realmEditionRefName; }

    @JsonProperty("provisioningMode")
    public String getProvisioningMode() { return provisioningMode; }

    @JsonProperty("provisioningMode")
    public void setProvisioningMode(String provisioningMode) { this.provisioningMode = provisioningMode; }

    @JsonProperty("setupStatus")
    public String getSetupStatus() { return setupStatus; }

    @JsonProperty("setupStatus")
    public void setSetupStatus(String setupStatus) { this.setupStatus = setupStatus; }

    @JsonProperty("setupCompletionPercent")
    public Integer getSetupCompletionPercent() { return setupCompletionPercent; }

    @JsonProperty("setupCompletionPercent")
    public void setSetupCompletionPercent(Integer setupCompletionPercent) { this.setupCompletionPercent = setupCompletionPercent; }
}
