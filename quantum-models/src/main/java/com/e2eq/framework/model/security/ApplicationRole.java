package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A role an {@link Application} declares. Only declared roles may be carried by a binding for the
 * application, and {@link #grantableBy} says who may grant it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ApplicationRole {

   /** Who may grant a role through a binding. The platform may always grant. */
   public static final String GRANTABLE_BY_TENANT_ADMIN = "tenant-admin";
   public static final String GRANTABLE_BY_REALM_ADMIN = "realm-admin";
   public static final String GRANTABLE_BY_PLATFORM = "platform";
   public static final List<String> GRANTABLE_BY_VALUES =
      List.of(GRANTABLE_BY_TENANT_ADMIN, GRANTABLE_BY_REALM_ADMIN, GRANTABLE_BY_PLATFORM);

   /** Role name as policies name it (Policy.principalId with principalType ROLE); no application prefix. */
   protected String name;
   protected String displayName;
   protected String description;
   @Builder.Default
   protected List<String> grantableBy = new ArrayList<>();
}
