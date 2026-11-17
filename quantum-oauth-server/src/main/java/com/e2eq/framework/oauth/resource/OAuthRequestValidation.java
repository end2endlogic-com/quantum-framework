package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.oauth.model.OAuthClient;

/** Protocol invariants shared by GET, form POST and code exchange, not an overrideable app policy. */
final class OAuthRequestValidation {
    private OAuthRequestValidation() { }

    static String authorizationError(OAuthClient client, String challenge, String method) {
        if (!client.isActive()) return "Client is inactive";
        if (client.getAllowedGrantTypes() == null
                || !client.getAllowedGrantTypes().contains("authorization_code")) {
            return "Client is not authorized for authorization_code";
        }
        return pkceError(client.isPublicClient(), challenge, method);
    }

    static String pkceError(boolean required, String challenge, String method) {
        boolean hasChallenge = challenge != null && !challenge.isBlank();
        boolean hasMethod = method != null && !method.isBlank();
        if (!hasChallenge) {
            return required || hasMethod ? "An S256 code_challenge is required" : null;
        }
        // S256 is the unpadded base64url SHA-256 digest (43 characters).
        if (!"S256".equals(method) || !challenge.matches("[A-Za-z0-9_-]{43}")) {
            return "A valid S256 code_challenge and code_challenge_method are required";
        }
        return null;
    }

    static boolean validVerifier(String verifier) {
        return verifier != null && verifier.matches("[A-Za-z0-9._~-]{43,128}");
    }
}
