package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Where an {@link Application} is reached in one deployment environment. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ApplicationLaunchAddress {
   /** The environment's name or ref (for example development or production). */
   protected String environment;
   /** The application's entry URL in that environment. */
   protected String url;
}
