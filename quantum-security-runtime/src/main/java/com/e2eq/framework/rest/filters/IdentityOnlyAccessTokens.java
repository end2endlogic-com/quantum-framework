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
                && DelegatedIdentityHeaders.ACCESS_TOKEN_TYPE.equalsIgnoreCase(text(token.getClaim("token_type")));
    }

    /**
     * Where this receiver resolves a v2 access token.
     *
     * @param application the application the principal is resolved for
     * @param authority   true at the identity authority (Auth), which configures no receiving
     *                    application and resolves the token for its own application
     */
    record Target(String application, boolean authority) {
    }

    /**
     * Fails closed, typed (401 {@value #UNRESOLVED}), when this receiver cannot resolve a v2 token,
     * else names where it resolves:
     * <ul>
     *   <li>a receiver that names its application ({@code quantum.security.session.application-id})
     *       resolves it in {@code IDENTITY} mode for that application, unless it reads token claims
     *       ({@code quantum.auth.trust-token-claims=true});</li>
     *   <li>the identity authority, which names no receiving application, resolves it for its own
     *       application ({@code quantum.auth.identity-authority.application-id}), whatever
     *       {@code trust-token-claims} says, as it admits v2 service tokens: its store is the one
     *       that resolves them;</li>
     *   <li>any other receiver, or one without a resolver, refuses.</li>
     * </ul>
     */
    static Target requireResolvable(boolean trustTokenClaims, String receivingApplication,
                                    String authorityApplication, Object resolver) {
        String reason;
        Target target = null;
        if (resolver == null) {
            reason = "it holds no credential store";
        } else if (receivingApplication != null) {
            reason = trustTokenClaims ? "it reads token claims (quantum.auth.trust-token-claims=true)" : null;
            target = new Target(receivingApplication, false);
        } else if (authorityApplication != null) {
            reason = null;
            target = new Target(authorityApplication, true);
        } else {
            reason = trustTokenClaims ? "it reads token claims (quantum.auth.trust-token-claims=true)"
                    : "it configures no quantum.security.session.application-id (the identity authority: no "
                            + "quantum.auth.identity-authority.application-id)";
        }
        if (reason != null) {
            throw new RemotePrincipalResolutionException(401, UNRESOLVED, UNRESOLVED_MESSAGE,
                    "An identity-only access token is resolved for an application from the credential; this receiver "
                            + "cannot, because " + reason + ": set quantum.auth.principal-source=remote");
        }
        return target;
    }

    /** {@link #requireResolvable(boolean, String, String, Object)} for a receiver that is not the authority. */
    static void requireResolvable(boolean trustTokenClaims, String application, Object resolver) {
        requireResolvable(trustTokenClaims, application, null, resolver);
    }

    static PrincipalContextRequest request(JsonWebToken token, Target target, String realm,
                                           UnaryOperator<String> header, boolean tenantSelectionOptional) {
        return target.authority()
                ? authorityRequest(token, target.application(), realm, header, tenantSelectionOptional)
                : request(token, target.application(), realm, header, tenantSelectionOptional);
    }

    static PrincipalContextRequest request(JsonWebToken token, String application, String realm,
                                           UnaryOperator<String> header, boolean tenantSelectionOptional) {
        return PrincipalContextRequest.identity(text(token.getClaim("sub")), text(token.getClaim("userId")))
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
     * At the identity authority: the request Auth's own operations resolve any bearer with (the local
     * credential path a v1 token takes there), with no token claim beyond the identity. The realm,
     * tenant and roles a v1 token carried are not in a v2 token, so the realm is the request's
     * selection (X-Realm) else the credential's home realm, the tenant the request's selection else
     * the credential's, and the roles the credential's grants in that realm for {@code application};
     * application admission does not apply, exactly as it does not for a v1 token there (Auth names
     * no session application). The application is Auth's own, where a v1 token supplied its
     * {@code azp}, so application-scoped policies apply as they did.
     */
    static PrincipalContextRequest authorityRequest(JsonWebToken token, String application, String realm,
                                                    UnaryOperator<String> header, boolean tenantSelectionOptional) {
        var claims = new PrincipalContextRequest.SignedClaims(null, null, null, false,
                trimToNull(header.apply("X-Realm")),
                trimToNull(header.apply(DelegatedIdentityHeaders.ORIGINAL_SUBJECT)),
                trimToNull(header.apply(DelegatedIdentityHeaders.ORIGINAL_USER_ID)),
                tenantSelectionOptional);
        PrincipalContextRequest.Builder builder = PrincipalContextRequest.signedTokenClaims(text(token.getClaim("sub")), claims)
                .applicationId(application)
                .realm(trimToNull(realm))
                .tenantId(trimToNull(header.apply("X-Tenant-Id")))
                .impersonate(trimToNull(header.apply("X-Impersonate-Subject")),
                        trimToNull(header.apply("X-Impersonate-UserId")))
                .actingOnBehalfOf(trimToNull(header.apply(DelegatedIdentityHeaders.EFFECTIVE_SUBJECT)),
                        trimToNull(header.apply(DelegatedIdentityHeaders.EFFECTIVE_USER_ID)));
        String userId = text(token.getClaim("userId"));
        if (userId != null) {
            builder.userIdCandidate(userId);
        }
        return builder.build();
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

    /** Identity claims are read by their literal names only (sub, userId, token_type). */
    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
