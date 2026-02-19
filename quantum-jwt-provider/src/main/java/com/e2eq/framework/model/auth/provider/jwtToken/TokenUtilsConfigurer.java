package com.e2eq.framework.model.auth.provider.jwtToken;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * CDI bean that configures {@link TokenUtils} key file locations at startup
 * from application.properties. If the properties are not set, the defaults
 * (classpath:privateKey.pem / classpath:publicKey.pem) are used.
 */
@ApplicationScoped
@Startup
public class TokenUtilsConfigurer {

    @ConfigProperty(name = "quantum.jwt.private-key-location")
    Optional<String> privateKeyLocation;

    @ConfigProperty(name = "quantum.jwt.public-key-location")
    Optional<String> publicKeyLocation;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        String privLoc = privateKeyLocation.orElse(null);
        String pubLoc = publicKeyLocation.orElse(null);

        if (privLoc != null || pubLoc != null) {
            Log.infof("Configuring TokenUtils key locations: private=%s, public=%s",
                    privLoc != null ? privLoc : "(default)",
                    pubLoc != null ? pubLoc : "(default)");
            TokenUtils.configure(privLoc, pubLoc);
        } else {
            Log.info("TokenUtils using default classpath key locations (privateKey.pem / publicKey.pem)");
        }
    }
}
