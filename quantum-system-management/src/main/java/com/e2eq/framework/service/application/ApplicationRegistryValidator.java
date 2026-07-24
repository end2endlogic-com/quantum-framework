package com.e2eq.framework.service.application;

import java.util.Collection;
import java.util.Set;

/**
 * Validation seam between the application-grant surface
 * ({@code UserRealmRoleResource}) and the platform application registry
 * (quantum-system-service's {@code ApplicationDefinition} catalog).
 *
 * The framework does not know where the registry lives — a deployment that
 * hosts one (e.g. quantum-auth-service alongside quantum-system-service)
 * contributes a bean that resolves application refNames against it. The
 * {@code @DefaultBean} implementation preserves legacy behavior by treating
 * every id as known, so embedded/single-app deployments are unaffected.
 *
 * The wildcard grant ({@code UserRealmRole.APPLICATION_WILDCARD}) is never
 * passed to this validator; callers strip it first.
 */
public interface ApplicationRegistryValidator {

   /**
    * @param applicationIds candidate application refNames (never null, no wildcard)
    * @return the subset of {@code applicationIds} unknown to the registry;
    *         empty when all resolve
    * @throws ApplicationRegistryUnavailableException when the registry cannot
    *         be consulted — callers must fail closed, not fall back to
    *         allow-all
    */
   Set<String> unknownApplications(Collection<String> applicationIds);
}
