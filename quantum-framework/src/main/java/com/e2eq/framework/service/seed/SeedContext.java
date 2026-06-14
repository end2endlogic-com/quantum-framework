package com.e2eq.framework.service.seed;

import java.util.Objects;
import java.util.Optional;

/**
 * Contextual information about the tenant/realm being seeded.
 */
public final class SeedContext {

    private final String realm;
    private final String tenantId;
    private final String orgRefName;
    private final String accountId;
    private final String ownerId;
    private final java.util.Map<String, String> variables;

    private SeedContext(Builder builder) {
        this.realm = Objects.requireNonNull(builder.realm, "realmId");
        this.tenantId = builder.tenantId;
        this.orgRefName = builder.orgRefName;
        this.accountId = builder.accountId;
        this.ownerId = builder.ownerId;
        this.variables = java.util.Map.copyOf(builder.variables);
    }

    public String getRealm () {
        return realm;
    }

    public Optional<String> getTenantId() {
        return Optional.ofNullable(tenantId);
    }

    public Optional<String> getOrgRefName() {
        return Optional.ofNullable(orgRefName);
    }

    public Optional<String> getAccountId() {
        return Optional.ofNullable(accountId);
    }

    public Optional<String> getOwnerId() {
        return Optional.ofNullable(ownerId);
    }

    public Optional<String> getVariable(String variableName) {
        if (variableName == null || variableName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(variables.get(variableName));
    }

    public java.util.Map<String, String> getVariables() {
        return variables;
    }

    public static Builder builder(String realmId) {
        return new Builder(realmId);
    }

    public static final class Builder {
        private final String realm;
        private String tenantId;
        private String orgRefName;
        private String accountId;
        private String ownerId;
        private final java.util.Map<String, String> variables = new java.util.LinkedHashMap<>();

        private Builder(String realm) {
            this.realm = Objects.requireNonNull(realm, "realm");
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder orgRefName(String orgRefName) {
            this.orgRefName = orgRefName;
            return this;
        }

        public Builder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder variable(String name, String value) {
            if (name != null && !name.isBlank() && value != null) {
                this.variables.put(name, value);
            }
            return this;
        }

        public SeedContext build() {
            return new SeedContext(this);
        }
    }
}
