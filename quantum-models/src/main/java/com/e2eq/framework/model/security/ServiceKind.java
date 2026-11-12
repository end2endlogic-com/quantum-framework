package com.e2eq.framework.model.security;

/**
 * What a service credential's identity-only token may do (design
 * {@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 4). Only meaningful for service
 * credentials; a null kind is never treated as {@link #DELEGATING}.
 *
 * <ul>
 *   <li>{@link #APPLICATION}: the service runs as itself, in the one realm and
 *       tenant of its own DomainContext. Acting on behalf of anyone is refused.</li>
 *   <li>{@link #DELEGATING}: a multi-tenant service. A request naming an
 *       acted-for identity resolves that identity (its realm, tenant, roles and
 *       policies) within the credential's impersonation authorization; without
 *       one the service runs as itself.</li>
 * </ul>
 */
public enum ServiceKind {
    APPLICATION,
    DELEGATING
}
