package com.e2eq.framework.rest.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    protected String access_token;
    protected String refresh_token;
    protected long expires_at;
    protected String mongodburl;
    protected String realm;
    protected List<String> roles;

    // Backward-compatible constructor
    public AuthResponse(String access_token, String refresh_token, long expires_at) {
        this.access_token = access_token;
        this.refresh_token = refresh_token;
        this.expires_at = expires_at;
    }
}
