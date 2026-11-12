package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.securityrules.PrincipalContext;

/**
 * Remote principal source ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 3, "Receiving
 * applications"). With {@code quantum.auth.principal-source=remote}, {@code SecurityFilter}
 * verifies the bearer's signature locally and then asks this source who the caller is for this
 * application, instead of reading roles, realm or tenancy from token claims.
 *
 * <p>An implementation must:
 * <ul>
 *   <li>return the principal Auth resolved, never one built from token claims;</li>
 *   <li>throw {@link RemotePrincipalResolutionException} carrying Auth's status and code for a
 *       typed refusal;</li>
 *   <li>throw {@link RemotePrincipalResolutionException#unavailable(String)} (503
 *       {@code PRINCIPAL_RESOLUTION_UNAVAILABLE}) when Auth cannot answer and it holds no
 *       unexpired resolution for the request.</li>
 * </ul>
 * There is no fallback: a source that cannot resolve fails the request.
 */
public interface RemotePrincipalSource {

    PrincipalContext resolve(RemotePrincipalRequest request);
}
