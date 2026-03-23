package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.model.auth.provider.jwtToken.TokenUtils;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Serves the JSON Web Key Set (JWKS) containing the public key used to verify
 * tokens issued by this OAuth server. Clients and resource servers use this
 * endpoint to obtain the key for JWT signature verification.
 */
@Path("/oauth/jwks")
@PermitAll
public class JwksResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String jwks() {
        try {
            PublicKey publicKey = TokenUtils.readPublicKey(TokenUtils.getPublicKeyLocation());
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) publicKey)
                    .keyID(TokenUtils.getPrivateKeyLocation())
                    .build();
            JWKSet jwkSet = new JWKSet(rsaKey);
            return jwkSet.toJSONObject().toString();
        } catch (Exception e) {
            Log.error("Failed to build JWKS", e);
            throw new IllegalStateException("Unable to serve JWKS", e);
        }
    }
}
