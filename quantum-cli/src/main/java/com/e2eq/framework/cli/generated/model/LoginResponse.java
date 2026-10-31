package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginResponse {
    private String accessToken;

    @JsonProperty("access_token")
    public String getAccessToken() { return accessToken; }

    @JsonProperty("access_token")
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
}
