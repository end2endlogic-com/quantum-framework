package com.e2eq.framework.rest.models;

public class AuthResponse {
    
    protected String access_token;
    protected String refresh_token;

    

    public AuthResponse() {
    }

    public AuthResponse(String token) {
        this.access_token = token;
    }

    public AuthResponse(String accessToken, String refreshToken) {
        this.access_token = accessToken;
        this.refresh_token = refreshToken;
    }

    public String getAccess_token() {
        return access_token;
    }

    public void setAccess_token(String token) {
        this.access_token = token;
    }

    public String getRefresh_token () {
        return refresh_token;
    }

    public void setRefresh_token (String refresh_token) {
        this.refresh_token = refresh_token;
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthResponse)) return false;

        AuthResponse that = (AuthResponse) o;

        if (access_token != null ? !access_token.equals(that.access_token) : that.access_token != null) return false;
        return refresh_token != null ? refresh_token.equals(that.refresh_token) : that.refresh_token == null;
    }

    @Override
    public int hashCode () {
        int result = access_token != null ? access_token.hashCode() : 0;
        result = 31 * result + (refresh_token != null ? refresh_token.hashCode() : 0);
        return result;
    }
}
