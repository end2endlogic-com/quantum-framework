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
    /** Tenants the caller may enter in the active realm. Dedicated realms return one. */
    protected List<AccessibleTenantInfo> accessibleTenants;
    /** Applications this token is valid for (the token's {@code aud} set). Null when app scoping is not configured. */
    protected List<String> applications;
    /** Registered application id actively entered (the token's {@code azp} claim). */
    protected String activeApplicationId;

    // Backward-compatible constructor
    public AuthResponse(String access_token, String refresh_token, long expires_at) {
        this.access_token = access_token;
        this.refresh_token = refresh_token;
        this.expires_at = expires_at;
    }

    /** @deprecated Use {@code access_token}; retained for 2.x wire compatibility. */
    @Deprecated
    @JsonProperty("accessToken")
    public String getAccessToken() {
        return access_token;
    }

    /** @deprecated Use {@code refresh_token}; retained for 2.x wire compatibility. */
    @Deprecated
    @JsonProperty("refreshToken")
    public String getRefreshToken() {
        return refresh_token;
    }

    /** @deprecated Use {@code expires_at}; retained for 2.x wire compatibility. */
    @Deprecated
    @JsonProperty("expiresAt")
    public long getExpiresAt() {
        return expires_at;
    }

    /** @deprecated Use {@link #getActiveApplicationId()}; retained for 2.x wire compatibility. */
    @Deprecated
    @JsonProperty("activeApplication")
    public String getActiveApplication() {
        return activeApplicationId;
    }

    /** @deprecated Use {@link #setActiveApplicationId(String)}; retained for 2.x source compatibility. */
    @Deprecated
    @JsonProperty("activeApplication")
    public void setActiveApplication(String activeApplication) {
        this.activeApplicationId = activeApplication;
    }
}
