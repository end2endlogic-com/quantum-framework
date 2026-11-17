package com.e2eq.framework.model.security;

/**
 * Where an {@link Application} is in its life. The public catalog lists ACTIVE and DEPRECATED;
 * a PREVIEW application is reachable by those who know it, not advertised.
 */
public enum ApplicationLifecycleState {
   DRAFT,
   PREVIEW,
   ACTIVE,
   DEPRECATED,
   RETIRED;

   public boolean catalogued() {
      return this == ACTIVE || this == DEPRECATED;
   }
}
