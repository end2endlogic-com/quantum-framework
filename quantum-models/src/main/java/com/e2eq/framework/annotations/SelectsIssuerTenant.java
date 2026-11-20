package com.e2eq.framework.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an operation that selects the caller's tenant itself, by the resolver's identity tenant
 * selection, and refuses an unusable selection with its own typed problem (Auth's service-token
 * issue). The security filter leaves the request's {@code X-Tenant-Id} to it instead of selecting
 * (and refusing, untyped) first. Matching is by resource method, never by path.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SelectsIssuerTenant {
}
