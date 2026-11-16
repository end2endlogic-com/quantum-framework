package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.security.principal.PrincipalContextRequest;
import com.e2eq.framework.security.principal.PrincipalContextResolution;
import com.e2eq.framework.security.principal.PrincipalContextResolver;
import com.e2eq.framework.security.principal.PrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.function.UnaryOperator;

/**
 * Token contract v2 user access tokens ({@code token_type=access}, {@code IDENTITY_ONLY_TOKENS_DESIGN.md}
 * section 1) on a receiver that holds the credential store, shared by {@link RolesAugmentor},
 * {@link IdentityOnlyAccessRefusalFilter} and {@link SecurityFilter} so all three ask the same
 * question: {@code PrincipalContextResolver} in {@code IDENTITY} mode for this application, with
 * the request's realm, tenant, impersonation and acting-on-behalf-of headers, exactly as Auth's
 * {@code /v1/auth/principal-context/resolve} answers a remote receiver. Nothing beyond {@code sub}
 * and {@code userId} is read from the token.
 */
final class IdentityOnlyAccessTokens {

    /** Typed refusal for a receiver that cannot resolve a v2 token itself. */
    static final String UNRESOLVED = "TOKEN_CONTRACT_V2_UNRESOLVED";
    static final String UNRESOLVED_MESSAGE =
            "This service cannot resolve an identity-only access token; it must resolve principals through Auth";

    private IdentityOnlyAccessTokens() {
    }

    static boolean isAccessToken(JsonWebToken token) {
        return token != null
                && DelegatedIdentityHeaders.ACCESS_TOKEN_TYPE.equalsIgnoreCase(claim(token, DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM));
    }

    /**
     * Fails closed, typed (401 {@value #UNRESOLVED}), when this receiver cannot resolve a v2 token:
     * it reads token claims ({@code quantum.auth.trust-token-claims=true}), names no application
     * ({@code quantum.security.session.application-id}) or has no resolver.
     */
    static void requireResolvable(boolean trustTokenClaims, String application, Object resolver) {
        String reason = trustTokenClaims ? "it reads token claims (quantum.auth.trust-token-claims=true)"
                : application == null ? "it configures no quantum.security.session.application-id"
                : resolver == null ? "it holds no credential store"
                : null;
        if (reason != null) {
            throw new RemotePrincipalResolutionException(401, UNRESOLVED, UNRESOLVED_MESSAGE,
                    "An identity-only access token is resolved for an application from the credential; this receiver "
                            + "cannot, because " + reason + ": set quantum.auth.principal-source=remote");
        }
    }

    static PrincipalContextRequest request(JsonWebToken token, String application, String realm,
                                           UnaryOperator<String> header, boolean tenantSelectionOptional) {
        return PrincipalContextRequest.identity(claim(token, "sub"), claim(token, "userId"))
                .applicationId(application)
                .realm(trimToNull(realm))
                .tenantId(trimToNull(header.apply("X-Tenant-Id")))
                .tenantSelectionOptional(tenantSelectionOptional)
                .impersonate(trimToNull(header.apply("X-Impersonate-Subject")),
                        trimToNull(header.apply("X-Impersonate-UserId")))
                .actingOnBehalfOf(trimToNull(header.apply(DelegatedIdentityHeaders.EFFECTIVE_SUBJECT)),
                        trimToNull(header.apply(DelegatedIdentityHeaders.EFFECTIVE_USER_ID)))
                .build();
    }

    /**
     * Routing-context key under which {@link RolesAugmentor} leaves its refusal for
     * {@link IdentityOnlyAccessRefusalFilter}, so the filter never resolves (or touches the
     * credential store) on the event loop itself.
     */
    static final String REFUSAL_KEY = IdentityOnlyAccessTokens.class.getName() + ".refusal";

    /**
     * Resolves, answering a refusal with its typed status and code. Any other failure of the
     * credential store or directory is a sanitized 503 {@code PRINCIPAL_RESOLUTION_UNAVAILABLE},
     * never a generic error carrying the exception's message.
     */
    static PrincipalContextResolution resolve(PrincipalContextResolver resolver, PrincipalContextRequest request) {
        try {
            return resolver.resolve(request);
        } catch (PrincipalResolutionException refused) {
            throw RemotePrincipalResolutionException.of(refused.error(), refused.getMessage());
        } catch (jakarta.ws.rs.WebApplicationException typed) {
            throw typed;
        } catch (RuntimeException failed) {
            throw RemotePrincipalResolutionException.unavailable(
                    "Identity-only token resolution failed: " + failed.getClass().getName());
        }
    }

    private static String claim(JsonWebToken token, String name) {
        Object value = token.getClaim(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
