package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    protected String access_token;
    protected String refresh_token;
    protected long expires_at;
    protected String mongodburl;
    protected String realm;
    protected List<String> roles;
    protected String authProvider;
    protected List<AccessibleRealmInfo> accessibleRealms;
    /** Applications this token is valid for (the token's {@code aud} set). Null when app scoping is not configured. */
    protected List<String> applications;
    /** The application actively entered (the token's {@code azp} claim). Null when app scoping is not configured. */
    protected String activeApplication;

    // Backward-compatible constructor
    public AuthResponse(String access_token, String refresh_token, long expires_at) {
        this.access_token = access_token;
        this.refresh_token = refresh_token;
        this.expires_at = expires_at;
    }

    @JsonProperty("accessToken")
    public String getAccessToken() {
        return access_token;
    }

    @JsonProperty("refreshToken")
    public String getRefreshToken() {
        return refresh_token;
    }

    @JsonProperty("expiresAt")
    public long getExpiresAt() {
        return expires_at;
    }
}
