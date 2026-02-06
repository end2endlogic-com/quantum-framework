package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Authentication configuration for an external tool provider.
 * Used by {@link ToolProviderConfig}.
 */
@RegisterForReflection
public class AuthConfig {

    /** none, bearer, api_key, oauth2, mtls */
    private String authType = "none";
    /** Reference to secret vault for credentials. */
    private String secretRef;
    /** For OAuth2 token endpoint. */
    private String tokenEndpoint;
    /** Header name for API key (e.g. X-API-Key). */
    private String headerName;

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }
}
