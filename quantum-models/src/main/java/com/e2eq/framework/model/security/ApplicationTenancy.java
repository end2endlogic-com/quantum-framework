package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Where an {@link Application}'s tenants live. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ApplicationTenancy {

   /** Tenants share the application's pooled realm(s), {@code <applicationId>-P<n>}. */
   public static final String PLACEMENT_POOLED = "POOLED";
   /** Each tenant gets its own realm. */
   public static final String PLACEMENT_DEDICATED = "DEDICATED";
   /** Tenants live in the realms listed in {@link #realms}. */
   public static final String PLACEMENT_REALMS = "REALMS";
   public static final List<String> PLACEMENT_VALUES = List.of(PLACEMENT_POOLED, PLACEMENT_DEDICATED, PLACEMENT_REALMS);

   protected String placement;
   @Builder.Default
   protected List<String> realms = new ArrayList<>();
}
