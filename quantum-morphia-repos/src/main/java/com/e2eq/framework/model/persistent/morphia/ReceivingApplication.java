package com.e2eq.framework.model.persistent.morphia;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * This application's id, the one Auth resolves the principal for
 * ({@code IDENTITY_ONLY_TOKENS_DESIGN.md}): {@code quantum.security.session.application-id}, else
 * {@code quantum.application-registration.application-id}. Code that needs the application a
 * request is for uses the resolved {@code PrincipalContext} application, then this; never a
 * token's {@code azp}.
 */
@ApplicationScoped
public class ReceivingApplication {

    public static final String SESSION_APPLICATION_ID = "quantum.security.session.application-id";
    public static final String REGISTRATION_APPLICATION_ID = "quantum.application-registration.application-id";

    @ConfigProperty(name = SESSION_APPLICATION_ID)
    Optional<String> sessionApplicationId = Optional.empty();

    @ConfigProperty(name = REGISTRATION_APPLICATION_ID)
    Optional<String> registrationApplicationId = Optional.empty();

    /** Only quantum-auth-service sets it: its credential store is the one that resolves tokens. */
    public static final String IDENTITY_AUTHORITY = "quantum.auth.identity-authority";

    /**
     * The application the identity authority evaluates its own operations for when the bearer is an
     * identity-only (token contract v2) access token, which names no application. Auth is
     * application-agnostic ({@link #applicationId()} is null there), so a v1 token's {@code azp}
     * used to supply it; a v2 token has none. Read only with {@value #IDENTITY_AUTHORITY}{@code =true}.
     */
    public static final String IDENTITY_AUTHORITY_APPLICATION_ID = "quantum.auth.identity-authority.application-id";

    @ConfigProperty(name = IDENTITY_AUTHORITY, defaultValue = "false")
    boolean identityAuthority;

    @ConfigProperty(name = IDENTITY_AUTHORITY_APPLICATION_ID)
    Optional<String> identityAuthorityApplicationId = Optional.empty();

    /** The configured application id, or null when none is configured. */
    public String applicationId() {
        return of(sessionApplicationId, registrationApplicationId);
    }

    /**
     * The identity authority's own application ({@value #IDENTITY_AUTHORITY_APPLICATION_ID}), or null
     * when this service is not the identity authority or configures none.
     */
    public String identityAuthorityApplicationId() {
        return identityAuthority ? configured(identityAuthorityApplicationId) : null;
    }

    /** The session application id when set, else the registration application id, else null. */
    public static String of(Optional<String> sessionApplicationId, Optional<String> registrationApplicationId) {
        String session = configured(sessionApplicationId);
        return session != null ? session : configured(registrationApplicationId);
    }

    private static String configured(Optional<String> value) {
        return value == null ? null : value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }
}
