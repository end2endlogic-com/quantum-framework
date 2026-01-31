package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Utility for managing security contexts using scoped, try-with-resources patterns.
 *
 * <p>This class provides a safe, nestable way to temporarily modify the security context
 * (PrincipalContext, ResourceContext) or bypass security rules entirely. All scope
 * operations use push/pop semantics internally, ensuring that previous contexts are
 * properly restored when the scope closes—even if an exception is thrown.</p>
 *
 * <h2>Core Concepts</h2>
 *
 * <h3>Push/Pop Context Management</h3>
 * <p>When you open a scope, the current context is pushed onto a thread-local stack,
 * and the new context is set. When the scope closes, the previous context is popped
 * from the stack and restored. This allows safe nesting of scopes:</p>
 *
 * <pre>{@code
 * // Outer scope: sets principal A and resource X
 * try (SecurityCallScope.Scope outer = SecurityCallScope.open(principalA, resourceX)) {
 *     // SecurityContext now has principalA and resourceX
 *
 *     // Inner scope: temporarily switch to resource Y
 *     try (SecurityCallScope.Scope inner = SecurityCallScope.openResourceOnly(resourceY)) {
 *         // SecurityContext now has principalA and resourceY
 *         doSomethingWithResourceY();
 *     }
 *     // Inner scope closed: resourceX is restored
 *     // SecurityContext now has principalA and resourceX again
 * }
 * // Outer scope closed: original contexts restored
 * }</pre>
 *
 * <h3>Ignore Rules Mode</h3>
 * <p>For internal/privileged queries (e.g., from AccessListResolvers), you can bypass
 * security rule evaluation entirely using {@link #openIgnoringRules()}. This is useful
 * when resolvers need to query helper entities (UserProfile, Territory, etc.) without
 * having security filters applied to those queries.</p>
 *
 * <pre>{@code
 * try (SecurityCallScope.Scope scope = SecurityCallScope.openIgnoringRules()) {
 *     // All repository queries here bypass security rules
 *     Optional<UserProfile> profile = userProfileRepo.getByUserId(userId);
 *     Optional<Associate> associate = associateRepo.findByUserProfileId(profileId);
 * }
 * // Security rules are automatically re-enabled
 * }</pre>
 *
 * <h2>Available Scope Types</h2>
 * <ul>
 *   <li>{@link #open(PrincipalContext, ResourceContext)} - Sets both contexts</li>
 *   <li>{@link #openResourceOnly(ResourceContext)} - Sets only ResourceContext, keeps current PrincipalContext</li>
 *   <li>{@link #openIgnoringRules()} - Bypasses security rule evaluation entirely</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All context state is stored in ThreadLocals. Scopes only affect the current thread
 * and are safe to use in multi-threaded environments. However, contexts do not propagate
 * to child threads automatically.</p>
 *
 * <h2>Exception Safety</h2>
 * <p>Because scopes implement {@link AutoCloseable}, using try-with-resources ensures
 * that contexts are properly restored even if an exception is thrown within the scope.</p>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Pattern 1: Non-REST callers (background jobs, CLI tools)</h3>
 * <pre>{@code
 * PrincipalContext principal = SecurityCallScope.service(realm, dataDomain, "system-job");
 * ResourceContext resource = SecurityCallScope.resource(principal, null, "jobs", "cleanup", "EXECUTE");
 *
 * try (SecurityCallScope.Scope scope = SecurityCallScope.open(principal, resource)) {
 *     // Execute job with proper security context
 *     jobService.runCleanup();
 * }
 * }</pre>
 *
 * <h3>Pattern 2: AccessListResolver internal queries</h3>
 * <pre>{@code
 * public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<?> modelClass) {
 *     try (SecurityCallScope.Scope scope = SecurityCallScope.openIgnoringRules()) {
 *         // Query helper entities without security filters
 *         UserProfile profile = userProfileRepo.getByUserId(pctx.getUserId()).orElse(null);
 *         if (profile == null) return Collections.emptyList();
 *
 *         Associate associate = associateRepo.findByUserProfileId(profile.getId()).orElse(null);
 *         // ... compute accessible resources
 *     }
 * }
 * }</pre>
 *
 * <h3>Pattern 3: Temporary context switch</h3>
 * <pre>{@code
 * // Switch to a different functional domain temporarily
 * ResourceContext auditContext = new ResourceContext.Builder()
 *     .withArea("audit")
 *     .withFunctionalDomain("logs")
 *     .withAction("WRITE")
 *     .build();
 *
 * try (SecurityCallScope.Scope scope = SecurityCallScope.openResourceOnly(auditContext)) {
 *     auditService.logAction(...);
 * }
 * // Original ResourceContext is restored
 * }</pre>
 *
 * @see SecurityContext
 * @see PrincipalContext
 * @see ResourceContext
 */
public final class SecurityCallScope {
  private SecurityCallScope() {}

  // ============================================================================
  // SCOPE OPENING METHODS
  // ============================================================================

  /**
   * Convenience method to run an action with explicit contexts and return a result.
   *
   * <p>This is a shorthand for:</p>
   * <pre>{@code
   * try (Scope scope = SecurityCallScope.open(principal, resource)) {
   *     return action.get();
   * }
   * }</pre>
   *
   * @param principal the PrincipalContext to set for the duration of the action
   * @param resource the ResourceContext to set for the duration of the action
   * @param action the action to execute; its return value is passed through
   * @param <T> the return type of the action
   * @return the result of the action
   */
  public static <T> T runWithContexts(PrincipalContext principal,
                                      ResourceContext resource,
                                      Supplier<T> action) {
    try (Scope ignored = open(principal, resource)) {
      return action.get();
    }
  }

  /**
   * Convenience method to run an action with explicit contexts (no return value).
   *
   * @param principal the PrincipalContext to set for the duration of the action
   * @param resource the ResourceContext to set for the duration of the action
   * @param action the action to execute
   * @see #runWithContexts(PrincipalContext, ResourceContext, Supplier)
   */
  public static void runWithContexts(PrincipalContext principal,
                                     ResourceContext resource,
                                     Runnable action) {
    try (Scope ignored = open(principal, resource)) {
      action.run();
    }
  }

  /**
   * Opens a scope that sets both PrincipalContext and ResourceContext.
   *
   * <p><b>Push/Pop Behavior:</b> The current contexts (if any) are pushed onto
   * thread-local stacks. When the scope closes, the previous contexts are popped
   * and restored. This allows safe nesting of scopes.</p>
   *
   * <p><b>Example:</b></p>
   * <pre>{@code
   * PrincipalContext principal = SecurityCallScope.service(realm, dataDomain, "batch-job");
   * ResourceContext resource = SecurityCallScope.resource(principal, null, "batch", "import", "EXECUTE");
   *
   * try (SecurityCallScope.Scope scope = SecurityCallScope.open(principal, resource)) {
   *     // Code here runs with the specified security context
   *     importService.runImport();
   * }
   * // Previous contexts (if any) are automatically restored
   * }</pre>
   *
   * @param principal the PrincipalContext to set; must not be null
   * @param resource the ResourceContext to set; must not be null
   * @return a Scope that restores previous contexts on close
   * @throws NullPointerException if either parameter is null
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
   *
   * <p>This is useful when you need to temporarily change the resource context
   * (area/functionalDomain/action) while keeping the same principal identity.
   * Common use cases include:</p>
   * <ul>
   *   <li>Writing audit logs with a different functional domain</li>
   *   <li>Querying related entities with a different action type</li>
   *   <li>Switching between functional areas within a single operation</li>
   * </ul>
   *
   * <p><b>Push/Pop Behavior:</b> Only the ResourceContext is pushed/popped.
   * The PrincipalContext remains unchanged throughout.</p>
   *
   * <p><b>Example:</b></p>
   * <pre>{@code
   * // During a Location LIST request, temporarily switch to audit logging
   * ResourceContext auditContext = new ResourceContext.Builder()
   *     .withArea("audit")
   *     .withFunctionalDomain("accessLog")
   *     .withAction("WRITE")
   *     .build();
   *
   * try (SecurityCallScope.Scope scope = SecurityCallScope.openResourceOnly(auditContext)) {
   *     auditService.logAccess(userId, "locations", "list");
   * }
   * // Original ResourceContext (locations/LIST) is restored
   * }</pre>
   *
   * @param resource the ResourceContext to set; must not be null
   * @return a Scope that restores the previous ResourceContext on close
   * @throws NullPointerException if resource is null
   */
  public static Scope openResourceOnly(ResourceContext resource) {
    Objects.requireNonNull(resource, "resource context cannot be null");

    // Only push ResourceContext - PrincipalContext remains unchanged
    SecurityContext.pushResourceContext(resource);

    return new Scope(false, true, false);
  }

  /**
   * Opens a scope that bypasses security rule evaluation entirely.
   *
   * <p><b>This is the preferred approach for internal/privileged queries</b> from
   * AccessListResolvers or other framework code that needs to query entities without
   * applying security filters. Without this, internal queries would:</p>
   * <ul>
   *   <li>Trigger recursive resolver invocations</li>
   *   <li>Generate "unresolved variable" warnings for inapplicable rules</li>
   *   <li>Potentially cause stack overflows or incorrect filtering</li>
   * </ul>
   *
   * <p><b>How it works:</b> While in this scope, {@link SecurityContext#isIgnoringRules()}
   * returns true. Repository query methods (e.g., {@code getFilterArray()}) check this
   * flag and skip rule evaluation when true. This is a depth-counted mechanism that
   * supports nesting.</p>
   *
   * <p><b>Security Considerations:</b> This bypasses security rules, so use it only for
   * internal framework queries where the calling code is already properly authorized.
   * The results should not be directly exposed to users without proper filtering.</p>
   *
   * <p><b>Example - AccessListResolver:</b></p>
   * <pre>{@code
   * @Override
   * public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<?> modelClass) {
   *     try (SecurityCallScope.Scope scope = SecurityCallScope.openIgnoringRules()) {
   *         // These queries run without security filters
   *         UserProfile profile = userProfileRepo.getByUserId(pctx.getUserId()).orElse(null);
   *         if (profile == null) return Collections.emptyList();
   *
   *         Associate associate = associateRepo.findByUserProfileId(profile.getId()).orElse(null);
   *         if (associate == null) return Collections.emptyList();
   *
   *         // Compute and return accessible resource identifiers
   *         return computeAccessibleLocations(associate);
   *     }
   *     // Security rules are automatically re-enabled
   * }
   * }</pre>
   *
   * @return a Scope that exits ignore-rules mode on close
   */
  public static Scope openIgnoringRules() {
    SecurityContext.enterIgnoreRulesMode();
    return new Scope(false, false, true);
  }

  // ============================================================================
  // SCOPE CLASS
  // ============================================================================

  /**
   * An AutoCloseable scope that restores previous security contexts on close.
   *
   * <p>Scopes track which contexts were modified when opened and restore them
   * appropriately when closed. This includes:</p>
   * <ul>
   *   <li>Popping PrincipalContext from the stack (if pushed)</li>
   *   <li>Popping ResourceContext from the stack (if pushed)</li>
   *   <li>Exiting ignore-rules mode (if entered)</li>
   * </ul>
   *
   * <p><b>Always use try-with-resources</b> to ensure proper cleanup:</p>
   * <pre>{@code
   * try (SecurityCallScope.Scope scope = SecurityCallScope.openIgnoringRules()) {
   *     // ... code that may throw exceptions
   * } // scope.close() is always called, even if an exception is thrown
   * }</pre>
   */
  public static final class Scope implements AutoCloseable {
    private final boolean popPrincipal;
    private final boolean popResource;
    private final boolean exitIgnoreRules;

    /** Creates a scope that pops both contexts on close. */
    private Scope() {
      this.popPrincipal = true;
      this.popResource = true;
      this.exitIgnoreRules = false;
    }

    /**
     * Creates a scope with specific cleanup behavior.
     *
     * @param popPrincipal whether to pop PrincipalContext on close
     * @param popResource whether to pop ResourceContext on close
     * @param exitIgnoreRules whether to exit ignore-rules mode on close
     */
    private Scope(boolean popPrincipal, boolean popResource, boolean exitIgnoreRules) {
      this.popPrincipal = popPrincipal;
      this.popResource = popResource;
      this.exitIgnoreRules = exitIgnoreRules;
    }

    /**
     * Restores the previous security context state.
     *
     * <p>Operations are performed in this order:</p>
     * <ol>
     *   <li>Exit ignore-rules mode (if applicable)</li>
     *   <li>Pop ResourceContext from stack (if applicable)</li>
     *   <li>Pop PrincipalContext from stack (if applicable)</li>
     * </ol>
     */
    @Override
    public void close() {
      // Exit ignore-rules mode first (if applicable)
      if (exitIgnoreRules) {
        SecurityContext.exitIgnoreRulesMode();
      }
      // Pop restores previous contexts from the stack
      if (popResource) {
        SecurityContext.popResourceContext();
      }
      if (popPrincipal) {
        SecurityContext.popPrincipalContext();
      }
    }
  }

  // ============================================================================
  // PRINCIPAL CONTEXT BUILDERS
  // ============================================================================
  // These factory methods create common PrincipalContext configurations.
  // Use these with open() or runWithContexts() to establish security contexts.

  /**
   * Creates a general-purpose PrincipalContext.
   *
   * @param defaultRealm the default realm/tenant for this principal
   * @param dataDomain the data domain containing org, account, and tenant info
   * @param userId the user identifier
   * @param scope the authentication scope (e.g., "oidc", "systemGenerated")
   * @param roles the roles assigned to this principal (varargs)
   * @return a configured PrincipalContext
   */
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

  /**
   * Creates a PrincipalContext with area-to-realm routing overrides.
   *
   * <p>Use this when different functional areas should route to different realms/tenants.</p>
   *
   * @param defaultRealm the default realm when no area override matches
   * @param dataDomain the data domain
   * @param userId the user identifier
   * @param scope the authentication scope
   * @param area2RealmOverrides map of area names to realm overrides
   * @param roles the roles assigned to this principal
   * @return a configured PrincipalContext with area routing
   */
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

  /**
   * Creates an anonymous PrincipalContext with the ANONYMOUS role.
   *
   * <p>Use this for unauthenticated access patterns where some operations
   * are allowed without login.</p>
   *
   * @param realm the realm for this anonymous context
   * @param dataDomain the data domain
   * @param anonymousUserId an identifier for the anonymous user (for logging)
   * @return an anonymous PrincipalContext
   */
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
