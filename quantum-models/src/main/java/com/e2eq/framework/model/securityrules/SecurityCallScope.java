package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Utility for non-REST callers to run code with an explicit PrincipalContext and ResourceContext
 * and to build those contexts easily.
 *
 * Thread-safety: uses SecurityContext's ThreadLocals; context is only applied to the current thread.
 */
public final class SecurityCallScope {
  private SecurityCallScope() {}

  // --------------------
  // Core run helpers
  // --------------------

  public static <T> T runWithContexts(PrincipalContext principal,
                                      ResourceContext resource,
                                      Supplier<T> action) {
    try (Scope ignored = open(principal, resource)) {
      return action.get();
    }
  }

  public static void runWithContexts(PrincipalContext principal,
                                     ResourceContext resource,
                                     Runnable action) {
    try (Scope ignored = open(principal, resource)) {
      action.run();
    }
  }

  /**
   * Opens a scope that sets the provided contexts and restores any previously set contexts on close.
   * Supports nested usage safely. Example:
   * try (SecurityCallScope.Scope scope = SecurityCallScope.open(p, r)) { ... }
   */
  public static Scope open(PrincipalContext principal, ResourceContext resource) {
    Objects.requireNonNull(principal, "principal context cannot be null");
    Objects.requireNonNull(resource, "resource context cannot be null");

    Optional<PrincipalContext> prevP = SecurityContext.getPrincipalContext();
    Optional<ResourceContext> prevR = SecurityContext.getResourceContext();

    SecurityContext.setPrincipalContext(principal); // validates fields
    SecurityContext.setResourceContext(resource);   // validates fields

    return new Scope(prevP.orElse(null), prevR.orElse(null));
  }

  /** Restores previous contexts on close (supports nesting). */
  public static final class Scope implements AutoCloseable {
    private final PrincipalContext previousPrincipal;
    private final ResourceContext previousResource;

    private Scope(PrincipalContext previousPrincipal, ResourceContext previousResource) {
      this.previousPrincipal = previousPrincipal;
      this.previousResource = previousResource;
    }

    @Override
    public void close() {
      // Restore prior contexts if present; otherwise clear TLs
      if (previousPrincipal != null) {
        SecurityContext.setPrincipalContext(previousPrincipal);
      } else {
        SecurityContext.clearPrincipalContext();
      }

      if (previousResource != null) {
        SecurityContext.setResourceContext(previousResource);
      } else {
        SecurityContext.clearResourceContext();
      }
    }
  }

  // --------------------
  // Principal builders
  // --------------------

  /** General-purpose principal factory. */
  public static PrincipalContext principal(String defaultRealm,
                                           DataDomain dataDomain,
                                           String userId,
                                           String scope,
                                           String... roles) {
    Objects.requireNonNull(defaultRealm, "defaultRealm");
    Objects.requireNonNull(dataDomain, "dataDomain");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(scope, "scope");

    return new PrincipalContext.Builder()
        .withDefaultRealm(defaultRealm)
        .withDataDomain(dataDomain)
        .withUserId(userId)
        .withRoles(roles == null ? new String[0] : roles)
        .withScope(scope)
        .build();
  }

  /** Principal with area-to-realm overrides, when routing varies by area. */
  public static PrincipalContext principalWithAreaOverrides(String defaultRealm,
                                                            DataDomain dataDomain,
                                                            String userId,
                                                            String scope,
                                                            Map<String, String> area2RealmOverrides,
                                                            String... roles) {
    return new PrincipalContext.Builder()
        .withDefaultRealm(defaultRealm)
        .withDataDomain(dataDomain)
        .withUserId(userId)
        .withRoles(roles == null ? new String[0] : roles)
        .withArea2RealmOverrides(area2RealmOverrides)
        .withScope(scope)
        .build();
  }

  /** Anonymous principal (uses provided realm and DataDomain). */
  public static PrincipalContext anonymous(String realm,
                                           DataDomain dataDomain,
                                           String anonymousUserId) {
    return new PrincipalContext.Builder()
        .withDefaultRealm(realm)
        .withDataDomain(dataDomain)
        .withUserId(anonymousUserId)
        .withRoles(new String[]{"ANONYMOUS"})
        .withScope("systemGenerated")
        .build();
  }

  /** Service principal for background jobs. */
  public static PrincipalContext service(String realm,
                                         DataDomain dataDomain,
                                         String serviceUserId,
                                         String... roles) {
    String[] effectiveRoles = (roles == null || roles.length == 0)
        ? new String[]{"SYSTEM"}
        : roles;
    return new PrincipalContext.Builder()
        .withDefaultRealm(realm)
        .withDataDomain(dataDomain)
        .withUserId(serviceUserId)
        .withRoles(effectiveRoles)
        .withScope("SYSTEM")
        .build();
  }

  /** Clone an existing principal but mark acting-on-behalf-of fields. */
  public static PrincipalContext actingOnBehalfOf(PrincipalContext base,
                                                  String aoboUserId,
                                                  String aoboSubject) {
    Objects.requireNonNull(base, "base principal");
    return new PrincipalContext.Builder()
        .withDefaultRealm(base.getDefaultRealm())
        .withDataDomain(base.getDataDomain())
        .withUserId(base.getUserId())
        .withRoles(base.getRoles())
        .withArea2RealmOverrides(base.getArea2RealmOverrides())
        .withScope(base.getScope())
        .withActingOnBehalfOfUserId(aoboUserId)
        .withActingOnBehalfOfSubject(aoboSubject)
        .build();
  }

  // --------------------
  // Resource builders
  // --------------------

  /** Build a minimal resource context. Realm defaults to principal.defaultRealm if null. */
  public static ResourceContext resource(PrincipalContext principal,
                                         String realmOrNull,
                                         String area,
                                         String functionalDomain,
                                         String action) {
    String realm = (realmOrNull != null) ? realmOrNull : principal.getDefaultRealm();
    return new ResourceContext.Builder()
        .withRealm(realm)
        .withArea(area)
        .withFunctionalDomain(functionalDomain)
        .withAction(action)
        .build();
  }

  /** Resource with known identifiers. */
  public static ResourceContext resourceWithIds(PrincipalContext principal,
                                                String realmOrNull,
                                                String area,
                                                String functionalDomain,
                                                String action,
                                                String resourceId,
                                                String ownerId) {
    String realm = (realmOrNull != null) ? realmOrNull : principal.getDefaultRealm();
    return new ResourceContext.Builder()
        .withRealm(realm)
        .withArea(area)
        .withFunctionalDomain(functionalDomain)
        .withAction(action)
        .withResourceId(resourceId)
        .withOwnerId(ownerId)
        .build();
  }

  // Convenience shorthands for common CRUD actions
  public static ResourceContext readResource(PrincipalContext p, String realmOrNull, String area, String fd) {
    return resource(p, realmOrNull, area, fd, "READ");
  }
  public static ResourceContext writeResource(PrincipalContext p, String realmOrNull, String area, String fd) {
    return resource(p, realmOrNull, area, fd, "WRITE");
  }
  public static ResourceContext deleteResource(PrincipalContext p, String realmOrNull, String area, String fd) {
    return resource(p, realmOrNull, area, fd, "DELETE");
  }
}
