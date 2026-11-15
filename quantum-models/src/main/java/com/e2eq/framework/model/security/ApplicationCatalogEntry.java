package com.e2eq.framework.model.security;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * The public, signed-out view of an {@link Application}: what a launch pad shows before sign-in.
 * It is a separate type so nothing else on the record (roles, tenancy, owner, packs, callback
 * allowlist, data domain, audit) can reach an unauthenticated caller by accident.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ApplicationCatalogEntry {

   protected String refName;
   protected String displayName;
   protected String appVersion;
   protected ApplicationLifecycleState lifecycleState;
   protected List<ApplicationLaunchAddress> launchAddresses;
   protected SignIn signIn;

   /** The sign-in route without its callback allowlist. */
   @Data
   @Builder
   @NoArgsConstructor
   @AllArgsConstructor
   @RegisterForReflection
   public static class SignIn {
      protected String clientId;
      protected String applicationEntryUrl;
      protected String authorizationPath;
   }

   /** Whether the application belongs in the public catalog: a lifecycle state that is catalogued. */
   public static boolean catalogued(Application application) {
      return application != null && application.getRefName() != null
         && application.getLifecycleState() != null && application.getLifecycleState().catalogued();
   }

   public static ApplicationCatalogEntry of(Application application) {
      Objects.requireNonNull(application, "application");
      ApplicationSignInRoute route = application.getSignInRoute();
      return ApplicationCatalogEntry.builder()
         .refName(application.getRefName())
         .displayName(application.getDisplayName())
         .appVersion(application.getAppVersion())
         .lifecycleState(application.getLifecycleState())
         .launchAddresses(application.getLaunchAddresses() == null ? List.of()
            : application.getLaunchAddresses().stream().filter(Objects::nonNull)
               .map(address -> ApplicationLaunchAddress.builder()
                  .environment(address.getEnvironment()).url(address.getUrl()).build())
               .toList())
         .signIn(route == null ? null : SignIn.builder()
            .clientId(route.getClientId())
            .applicationEntryUrl(route.getApplicationEntryUrl())
            .authorizationPath(route.getAuthorizationPath())
            .build())
         .build();
   }
}
