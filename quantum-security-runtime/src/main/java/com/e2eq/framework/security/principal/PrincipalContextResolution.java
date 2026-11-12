package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.PrincipalContext;

/**
 * The result of {@link PrincipalContextResolver#resolve}.
 *
 * @param context          the principal the request runs as: realm, DomainContext, data domain,
 *                         roles, area overrides and data-domain policy
 * @param credential       the credential {@code context} was built from (the impersonated or
 *                         acted-for identity when one applies)
 * @param callerCredential the credential the verified token identifies
 * @param impersonatedBy   the impersonator, when impersonation applied
 * @param actingService    the delegating service, when the request acted on behalf of an identity
 */
public record PrincipalContextResolution(PrincipalContext context,
                                         CredentialUserIdPassword credential,
                                         CredentialUserIdPassword callerCredential,
                                         PrincipalRef impersonatedBy,
                                         PrincipalRef actingService) {
}
