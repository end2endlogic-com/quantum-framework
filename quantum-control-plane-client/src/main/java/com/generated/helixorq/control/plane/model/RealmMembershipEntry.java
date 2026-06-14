package com.generated.helixorq.control.plane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

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
}
