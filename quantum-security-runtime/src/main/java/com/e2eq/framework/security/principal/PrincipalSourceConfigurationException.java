package com.e2eq.framework.security.principal;

import java.util.Objects;

/**
 * Startup refusal of a principal-source configuration that cannot run safely
 * ({@link RemotePrincipalSettings}). The message starts with the code, so a failed boot names it.
 */
public class PrincipalSourceConfigurationException extends IllegalStateException {

    /** Why the configuration was refused. */
    public enum Code {
        /** {@code quantum.auth.principal-source} is neither {@code remote} nor unset. */
        PRINCIPAL_SOURCE_INVALID,
        /** Remote mode without exactly one {@link RemotePrincipalSource} bean. */
        PRINCIPAL_SOURCE_UNAVAILABLE,
        /** Remote mode without this application's id. */
        PRINCIPAL_SOURCE_APPLICATION_MISSING,
        /** Remote mode with an application-session realm or selection policy, which it does not apply. */
        PRINCIPAL_SOURCE_SESSION_REALM,
        /**
         * A deployment (not dev or test) whose configuration declares remote mode for its profile,
         * but a higher-priority source (an environment variable, a system property) turned it off.
         */
        PRINCIPAL_SOURCE_NOT_REMOTE,
        /** Remote mode together with {@code quantum.auth.trust-token-claims=true}. */
        PRINCIPAL_SOURCE_TRUSTS_TOKEN_CLAIMS,
        /**
         * Remote mode together with audience admission ({@code quantum.auth.expected-audience} or
         * {@code mp.jwt.verify.audiences}): the application is the one Auth resolves the principal
         * for, never the token's {@code aud} or {@code azp}.
         */
        PRINCIPAL_SOURCE_AUDIENCE_CONFIGURED,
        /**
         * Remote mode with lazy authentication ({@code quarkus.http.auth.proactive=false}): the
         * pre-matching refusal filter reads the identity on the IO thread, which lazy
         * authentication refuses, so every bearer request would answer 500.
         */
        PRINCIPAL_SOURCE_LAZY_AUTHENTICATION
    }

    private final Code code;

    public PrincipalSourceConfigurationException(Code code, String detail) {
        super(Objects.requireNonNull(code, "code") + ": " + detail);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
