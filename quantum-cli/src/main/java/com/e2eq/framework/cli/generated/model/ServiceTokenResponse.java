package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceTokenResponse {
    @NotNull
    private String token;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String credentialId;
    @NotNull
    private String subject;
    @NotNull
    private String userId;
    @NotNull
    private String kind;
    @NotNull
    private String realm;
    private String tenantId;
    private String description;
    @JsonIgnore
    private final Set<String> $present = new HashSet<>();

    @JsonProperty("token")
    public String getToken() { return token; }

    @JsonSetter(nulls = Nulls.FAIL)
    @JsonProperty("token")
    public void setToken(String token) { this.token = token; }

    @JsonProperty("credentialId")
    public String getCredentialId() { return credentialId; }

    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("credentialId")
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }

    @JsonProperty("subject")
    public String getSubject() { return subject; }

    @JsonSetter(nulls = Nulls.FAIL)
    @JsonProperty("subject")
    public void setSubject(String subject) { this.subject = subject; }

    @JsonProperty("userId")
    public String getUserId() { return userId; }

    @JsonSetter(nulls = Nulls.FAIL)
    @JsonProperty("userId")
    public void setUserId(String userId) { this.userId = userId; }

    @JsonProperty("kind")
    public String getKind() { return kind; }

    @JsonSetter(nulls = Nulls.FAIL)
    @JsonProperty("kind")
    public void setKind(String kind) { this.kind = kind; }

    @JsonProperty("realm")
    public String getRealm() { return realm; }

    @JsonSetter(nulls = Nulls.FAIL)
    @JsonProperty("realm")
    public void setRealm(String realm) { this.realm = realm; }

    @JsonIgnore
    public String getTenantId() { return tenantId; }

    @JsonProperty("tenantId")
    public void setTenantId(String tenantId) { this.tenantId = tenantId; this.$present.add("tenantId"); }

    /** Whether tenantId was set or read from the wire; an unset tenantId is omitted from the JSON body. */
    public boolean hasTenantId() { return $present.contains("tenantId"); }

    @JsonProperty("tenantId")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AtomicReference<String> tenantId$wire() {
        return hasTenantId() ? new AtomicReference<>(tenantId) : null;
    }

    @JsonIgnore
    public String getDescription() { return description; }

    @JsonProperty("description")
    public void setDescription(String description) { this.description = description; this.$present.add("description"); }

    /** Whether description was set or read from the wire; an unset description is omitted from the JSON body. */
    public boolean hasDescription() { return $present.contains("description"); }

    @JsonProperty("description")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AtomicReference<String> description$wire() {
        return hasDescription() ? new AtomicReference<>(description) : null;
    }
}
