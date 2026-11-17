package com.e2eq.framework.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the one kind of operation at an application-agnostic receiver (Auth) where a delegating
 * service may act on behalf of an identity: a decision operation that names the application it
 * decides for in its {@code applicationId} query parameter ({@code GET /v1/authz/filter}).
 *
 * <p>This is the security filter's allowlist. At a receiver that configures no application of its
 * own, a request carrying {@code X-Acting-On-Behalf-Of-UserId|Subject} is resolved as a delegation
 * only when its resource method carries this annotation; every other operation refuses the header
 * with 403 {@code DELEGATION_NOT_ALLOWED}, whatever its query parameters. Matching is by resource
 * method, never by path. Add it only to an operation that returns a decision and changes nothing.
 *
 * <p>The caller of such an operation is resolved in identity mode, as
 * {@code POST /v1/auth/principal-context/resolve} resolves it: its roles, realm and tenant come only
 * from the store (memberships, assignments, bindings), never from the bearer's claims, so a decision
 * made for another application cannot be widened by a re-signed or stale token.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DelegatedDecisionOperation {
}
