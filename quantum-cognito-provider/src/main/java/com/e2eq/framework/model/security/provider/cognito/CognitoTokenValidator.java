package com.e2eq.framework.model.security.provider.cognito;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jose4j.jwk.PublicJsonWebKey;

/**
 * CognitoTokenValidator is a utility class that validates JWT tokens issued by AWS Cognito.
 * It fetches the public keys from the Cognito User Pool and uses them to verify the token's signature.
 */
@ApplicationScoped
public class CognitoTokenValidator {

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @ConfigProperty(name = "aws.cognito.region")
    String awsRegion;

    @ConfigProperty(name = "aws.cognito.user-pool-id")
    String userPoolId;

    @ConfigProperty(name = "aws.cognito.client-id")
    String clientId;

    @Inject
    JWTParser jwtParser;

    private final ConcurrentHashMap<String, PublicKey> keyCache =
        new ConcurrentHashMap<>();

    /**
     * Validates a JWT token issued by AWS Cognito.
     * @param token The JWT token to validate.
     * @return The validated JWT token.
     * @throws Exception If the token is invalid or cannot be validated.
     */
    public JsonWebToken validateToken(String token) throws Exception {
        String kid = extractKidFromToken(token);
        PublicKey publicKey = getPublicKey(kid);

        if (publicKey == null) {
            throw new ParseException(
                "Unable to find public key for kid: " + kid
            );
        }

        // Verify the token using SmallRye JWT
        JsonWebToken webToken = jwtParser.verify(token, publicKey);

        // Validate the claims
        validateClaims(webToken);
        return webToken;
    }

    private PublicKey getPublicKey(String kid) throws Exception {
        PublicKey publicKey = keyCache.get(kid);
        if (publicKey != null) {
            return publicKey;
        }

        String jwksJson = fetchJwks();
        try (
            JsonReader jsonReader = Json.createReader(
                new StringReader(jwksJson)
            )
        ) {
            JsonObject jwks = jsonReader.readObject();
            JsonArray keys = jwks.getJsonArray("keys");

            for (int i = 0; i < keys.size(); i++) {
                JsonObject key = keys.getJsonObject(i);
                if (kid.equals(key.getString("kid"))) {
                    // Use jose4j to create public key from JWK
                    PublicJsonWebKey jwk =
                        PublicJsonWebKey.Factory.newPublicJwk(key.toString());
                    publicKey = jwk.getPublicKey();
                    keyCache.put(kid, publicKey);
                    return publicKey;
                }
            }
        }

        return null;
    }

    private String fetchJwks() throws Exception {
        URI uri = new URI(
            String.format(
                "https://cognito-idp.%s.amazonaws.com/%s/.well-known/jwks.json",
                awsRegion,
                userPoolId
            )
        );
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        try (Scanner scanner = new Scanner(conn.getInputStream())) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private String extractKidFromToken(String token) throws ParseException {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new ParseException("Invalid JWT token format");
            }

            String headerJson = new String(
                Base64.getUrlDecoder().decode(parts[0])
            );
            try (
                JsonReader jsonReader = Json.createReader(
                    new StringReader(headerJson)
                )
            ) {
                JsonObject header = jsonReader.readObject();
                if (!header.containsKey("kid")) {
                    throw new ParseException(
                        "Token header missing 'kid' claim"
                    );
                }
                return header.getString("kid");
            }
        } catch (Exception e) {
            throw new ParseException("Failed to extract kid from token", e);
        }
    }

    private void validateClaims(JsonWebToken webToken) throws ParseException {
        // Verify issuer
        String expectedIssuer = String.format(
            "https://cognito-idp.%s.amazonaws.com/%s",
            awsRegion,
            userPoolId
        );
        String tokenIssuer = webToken.getIssuer();
        if (!expectedIssuer.equals(tokenIssuer)) {
            throw new ParseException("Invalid token issuer");
        }

        // use the webToken to validate if it is used for access
        String tokenUse = webToken.getClaim("token_use");
        if (!tokenUse.equals("access") && !tokenUse.equals("id")) {
            throw new ParseException("Invalid token_use claim");
        }

        // For access tokens, verify client_id
        if ("access".equals(tokenUse)) {
            String tokenClientId = webToken.getClaim("client_id");
            if (!clientId.equals(tokenClientId)) {
                throw new ParseException("Invalid client_id claim");
            }
        }

        // Verify expiration
        long expiration = webToken.getExpirationTime();
        if (expiration < (System.currentTimeMillis() / 1000)) {
            throw new ParseException("Token has expired");
        }
    }
}
