package com.e2eq.framework.cli.generated.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginResponse {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String accessToken;

    @JsonProperty("access_token")
    public String getAccessToken() { return accessToken; }

    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("access_token")
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
}
