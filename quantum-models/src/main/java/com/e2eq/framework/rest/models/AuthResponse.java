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
    /**
     * Never populated. It once carried the auth service's own MongoDB connection
     * string, credentials included. Kept only so the published contract does not
     * change; {@link JsonInclude.Include#NON_NULL} keeps it off the wire.
     */
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
    /**
     * Where to send the browser after a sign-in link or code is verified: the destination the
     * authority checked against the application's allowlist when the challenge started. Set only
     * by a verified sign-in challenge that stored one still allowed; absent everywhere else.
     */
    @org.eclipse.microprofile.openapi.annotations.media.Schema(description = "Where to send the browser after "
            + "a verified sign-in link, as checked against the application's allowlist when the challenge started. "
            + "Sent only by a sign-in challenge verification that stored one and still allows it.")
    protected String redirect;

    /** The all-arguments constructor as it was before {@code redirect}; keeps its descriptor. */
    public AuthResponse(String access_token, String refresh_token, long expires_at, String mongodburl,
                        String realm, List<String> roles, String authProvider,
                        List<AccessibleRealmInfo> accessibleRealms, List<AccessibleTenantInfo> accessibleTenants,
                        List<String> applications, String activeApplicationId) {
        this(access_token, refresh_token, expires_at, mongodburl, realm, roles, authProvider, accessibleRealms,
                accessibleTenants, applications, activeApplicationId, null);
    }

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

    /** @deprecated Use {@code activeApplicationId}; retained for 2.x wire compatibility. */
    @Deprecated
    @JsonProperty("activeApplication")
    public String getActiveApplication() {
        return activeApplicationId;
    }

    /** @deprecated Use {@code activeApplicationId}; retained for 2.x source compatibility. */
    @Deprecated
    @JsonProperty("activeApplication")
    public void setActiveApplication(String activeApplication) {
        this.activeApplicationId = activeApplication;
    }
}
