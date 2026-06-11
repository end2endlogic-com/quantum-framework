package com.e2eq.framework.api.tenant;

import java.util.List;
import java.util.Objects;

/**
 * Control-plane contract: request to provision a tenant (realm + org + account
 * + admin identity + seed archetypes). Mirrors the embedded
 * TenantProvisioningService command so existing callers keep their signatures
 * (wp3 compatibility rule 2); the contract type is what crosses the
 * plane boundary and what the Phase C remote client serializes.
 */
public final class TenantProvisionRequest {

    private final String tenantDisplayName;
    private final String tenantEmailDomain;
    private final String orgRefName;
    private final String accountId;
    private final String adminUserId;
    private final String adminSubject;
    private final String adminPassword;
    private final List<String> archetypes;
    private final boolean overwriteAll;

    private TenantProvisionRequest(Builder b) {
        this.tenantDisplayName = b.tenantDisplayName;
        this.tenantEmailDomain = Objects.requireNonNull(b.tenantEmailDomain, "tenantEmailDomain cannot be null");
        this.orgRefName = Objects.requireNonNull(b.orgRefName, "orgRefName cannot be null");
        this.accountId = Objects.requireNonNull(b.accountId, "accountId cannot be null");
        this.adminUserId = Objects.requireNonNull(b.adminUserId, "adminUserId cannot be null");
        this.adminSubject = b.adminSubject;
        this.adminPassword = b.adminPassword;
        this.archetypes = b.archetypes == null ? List.of() : List.copyOf(b.archetypes);
        this.overwriteAll = b.overwriteAll;
    }

    public String tenantDisplayName() { return tenantDisplayName; }
    public String tenantEmailDomain() { return tenantEmailDomain; }
    public String orgRefName() { return orgRefName; }
    public String accountId() { return accountId; }
    public String adminUserId() { return adminUserId; }
    public String adminSubject() { return adminSubject; }
    public String adminPassword() { return adminPassword; }
    public List<String> archetypes() { return archetypes; }
    public boolean overwriteAll() { return overwriteAll; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String tenantDisplayName;
        private String tenantEmailDomain;
        private String orgRefName;
        private String accountId;
        private String adminUserId;
        private String adminSubject;
        private String adminPassword;
        private List<String> archetypes = List.of();
        private boolean overwriteAll = true;

        public Builder tenantDisplayName(String v) { this.tenantDisplayName = v; return this; }
        public Builder tenantEmailDomain(String v) { this.tenantEmailDomain = v; return this; }
        public Builder orgRefName(String v) { this.orgRefName = v; return this; }
        public Builder accountId(String v) { this.accountId = v; return this; }
        public Builder adminUserId(String v) { this.adminUserId = v; return this; }
        public Builder adminSubject(String v) { this.adminSubject = v; return this; }
        public Builder adminPassword(String v) { this.adminPassword = v; return this; }
        public Builder archetypes(List<String> v) { this.archetypes = v; return this; }
        public Builder overwriteAll(boolean v) { this.overwriteAll = v; return this; }

        public TenantProvisionRequest build() { return new TenantProvisionRequest(this); }
    }
}
