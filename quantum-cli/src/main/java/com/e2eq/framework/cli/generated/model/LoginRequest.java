package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginRequest {
    @NotNull
    private String userId;
    @NotNull
    private String password;

    @JsonProperty("userId")
    public String getUserId() { return userId; }

    @JsonProperty("userId")
    public void setUserId(String userId) { this.userId = userId; }

    @JsonProperty("password")
    public String getPassword() { return password; }

    @JsonProperty("password")
    public void setPassword(String password) { this.password = password; }
}
