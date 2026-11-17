package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** How a signed-out visitor signs in to an {@link Application} through Auth. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ApplicationSignInRoute {
   /** The application's public OAuth client id. */
   protected String clientId;
   /** Application URL opened after a successful sign-in. */
   protected String applicationEntryUrl;
   /** Exact callback allowlist; not part of the public catalog. */
   @Builder.Default
   protected List<String> callbackUris = new ArrayList<>();
   /** Path on the environment's Auth service. */
   protected String authorizationPath;
}
