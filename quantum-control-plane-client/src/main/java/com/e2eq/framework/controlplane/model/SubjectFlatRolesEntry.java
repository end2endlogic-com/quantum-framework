package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SubjectFlatRolesEntry {
    @NotNull
    private String subject;
    @NotNull
    private String userId;
    @NotNull
    private Boolean active;
    @NotNull
    private List<String> roles;

    @JsonProperty("subject")
    public String getSubject() { return subject; }

    @JsonProperty("subject")
    public void setSubject(String subject) { this.subject = subject; }

    @JsonProperty("userId")
    public String getUserId() { return userId; }

    @JsonProperty("userId")
    public void setUserId(String userId) { this.userId = userId; }

    @JsonProperty("active")
    public Boolean getActive() { return active; }

    @JsonProperty("active")
    public void setActive(Boolean active) { this.active = active; }

    @JsonProperty("roles")
    public List<String> getRoles() { return roles; }

    @JsonProperty("roles")
    public void setRoles(List<String> roles) { this.roles = roles; }
}
