package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceTokenRequest {
    @NotNull
    private List<String> roles;
    private Long expirationSeconds;
    private String description;
    private String realm;
    private List<String> audiences;

    @JsonProperty("roles")
    public List<String> getRoles() { return roles; }

    @JsonProperty("roles")
    public void setRoles(List<String> roles) { this.roles = roles; }

    @JsonProperty("expirationSeconds")
    public Long getExpirationSeconds() { return expirationSeconds; }

    @JsonProperty("expirationSeconds")
    public void setExpirationSeconds(Long expirationSeconds) { this.expirationSeconds = expirationSeconds; }

    @JsonProperty("description")
    public String getDescription() { return description; }

    @JsonProperty("description")
    public void setDescription(String description) { this.description = description; }

    @JsonProperty("realm")
    public String getRealm() { return realm; }

    @JsonProperty("realm")
    public void setRealm(String realm) { this.realm = realm; }

    @JsonProperty("audiences")
    public List<String> getAudiences() { return audiences; }

    @JsonProperty("audiences")
    public void setAudiences(List<String> audiences) { this.audiences = audiences; }
}
