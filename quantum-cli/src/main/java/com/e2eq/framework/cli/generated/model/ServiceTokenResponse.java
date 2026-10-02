package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceTokenResponse {
    @NotNull
    private String token;
    @NotNull
    private String subject;
    private List<String> roles;
    private Boolean nonExpiring;
    private String expiresAt;
    private String description;

    @JsonProperty("token")
    public String getToken() { return token; }

    @JsonProperty("token")
    public void setToken(String token) { this.token = token; }

    @JsonProperty("subject")
    public String getSubject() { return subject; }

    @JsonProperty("subject")
    public void setSubject(String subject) { this.subject = subject; }

    @JsonProperty("roles")
    public List<String> getRoles() { return roles; }

    @JsonProperty("roles")
    public void setRoles(List<String> roles) { this.roles = roles; }

    @JsonProperty("nonExpiring")
    public Boolean getNonExpiring() { return nonExpiring; }

    @JsonProperty("nonExpiring")
    public void setNonExpiring(Boolean nonExpiring) { this.nonExpiring = nonExpiring; }

    @JsonProperty("expires_at")
    public String getExpiresAt() { return expiresAt; }

    @JsonProperty("expires_at")
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }

    @JsonProperty("description")
    public String getDescription() { return description; }

    @JsonProperty("description")
    public void setDescription(String description) { this.description = description; }
}
