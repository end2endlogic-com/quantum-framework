package com.e2eq.framework.service.application;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Set;

/**
 * Legacy behavior: no registry to consult, every application id is accepted.
 * Deployments wired to the platform registry replace this bean (see
 * quantum-auth-service's registry-backed implementation).
 */
@ApplicationScoped
@DefaultBean
public class LegacyAllowAllApplicationRegistryValidator implements ApplicationRegistryValidator {

   @Override
   public Set<String> unknownApplications(Collection<String> applicationIds) {
      return Set.of();
   }
}
