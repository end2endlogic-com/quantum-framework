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
   * Supports nested usage safely via push/pop. Example:
   * try (SecurityCallScope.Scope scope = SecurityCallScope.open(p, r)) { ... }
   */
  public static Scope open(PrincipalContext principal, ResourceContext resource) {
    Objects.requireNonNull(principal, "principal context cannot be null");
    Objects.requireNonNull(resource, "resource context cannot be null");

    // Use push to properly support nesting - previous contexts are saved on a stack
    SecurityContext.pushPrincipalContext(principal);
    SecurityContext.pushResourceContext(resource);

    return new Scope();
  }

  /**
   * Opens a scope that sets only a new ResourceContext, keeping the current PrincipalContext.
   * Useful for internal queries that need a different area/domain/action.
   * Example:
   * try (SecurityCallScope.Scope scope = SecurityCallScope.openResourceOnly(r)) { ... }
   */
  public static Scope openResourceOnly(ResourceContext resource) {
    Objects.requireNonNull(resource, "resource context cannot be null");

    // Only push ResourceContext - PrincipalContext remains unchanged
    SecurityContext.pushResourceContext(resource);

    return new Scope(false, true);
  }

  /** Restores previous contexts on close (supports nesting via pop). */
  public static final class Scope implements AutoCloseable {
    private final boolean popPrincipal;
    private final boolean popResource;

    private Scope() {
      this.popPrincipal = true;
      this.popResource = true;
    }

    private Scope(boolean popPrincipal, boolean popResource) {
      this.popPrincipal = popPrincipal;
      this.popResource = popResource;
    }

    @Override
    public void close() {
      // Pop restores previous contexts from the stack
      if (popResource) {
        SecurityContext.popResourceContext();
      }
      if (popPrincipal) {
        SecurityContext.popPrincipalContext();
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
