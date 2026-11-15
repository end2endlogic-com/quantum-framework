package com.e2eq.framework.model.security;

/** Where an {@link Application} is in its life. The public catalog lists PREVIEW, ACTIVE and DEPRECATED. */
public enum ApplicationLifecycleState {
   DRAFT,
   PREVIEW,
   ACTIVE,
   DEPRECATED,
   RETIRED;

   public boolean catalogued() {
      return this == PREVIEW || this == ACTIVE || this == DEPRECATED;
   }
}
