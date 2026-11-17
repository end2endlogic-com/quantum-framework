package com.e2eq.framework.rest.filters;


import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.ClaimsAuthProvider;
import com.e2eq.framework.model.auth.ProviderClaims;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.system.membership.RealmMembershipService;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Provider
@Priority(SecurityFilter.SECURITY_FILTER_PRIORITY)
//@PreMatching
//@PermissionCheck
public class SecurityFilter implements ContainerRequestFilter, jakarta.ws.rs.container.ContainerResponseFilter {

    /** Security context setup precedes post-auth governance; cleanup follows its response observation. */
    public static final int SECURITY_FILTER_PRIORITY = Priorities.USER;

    @jakarta.ws.rs.core.Context
    jakarta.ws.rs.container.ResourceInfo resourceInfo;

    private static final String AUTHENTICATION_SCHEME = "Bearer";

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    @Inject
    SessionSelectionCoordinator sessionSelection;

    @Inject
    RealmRepo realmRepo;

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    RealmMembershipService realmMembershipService;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    UserGroupRepo userGroupRepo;

    @Inject
    ObjectMapper mapper;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SecurityUtils securityUtils;

    // Scripting controls (sandbox & timeout)
    @ConfigProperty(name = "quantum.security.scripting.enabled", defaultValue = "true")
    boolean scriptingEnabled;

    @ConfigProperty(name = "quantum.security.scripting.timeout.millis", defaultValue = "250")
    long scriptingTimeoutMillis;

    @ConfigProperty(name = "quantum.security.scripting.allowAllAccess", defaultValue = "false")
    boolean scriptingAllowAllAccess;

    @ConfigProperty(name = "quantum.security.scripting.maxStatements", defaultValue = "10000")
    long scriptingMaxStatements;

    @ConfigProperty(name = "quantum.security.filter.enforcePreAuth", defaultValue = "true")
    boolean enforcePreAuth;

    // Delegated identity: when true, the configured ClaimsAuthProvider validates the bearer token and
    // supplies provider-neutral claims before any local identity repository is touched. This lets a
    // service delegate identity entirely to its provider without sharing a credential database.
    // Default false preserves the embedded/local-credential behavior.
    @ConfigProperty(name = "quantum.auth.trust-token-claims", defaultValue = "false")
    boolean trustTokenClaims;

    // Application session (opt-in). The verified token only says who the user is; when these are
    // set, a user's session runs as this application in this realm, whichever application or realm
    // the token was minted for (for example a launch pad handoff). Roles resolve through
    // IdentityRoleResolver for (userId, session realm, session application). An explicit X-Realm
    // still applies with its own checks. Service tokens and anonymous requests are unchanged.
    // Unset keeps the token's azp and realm.
    @ConfigProperty(name = "quantum.security.session.application-id")
    Optional<String> sessionApplicationId = Optional.empty();

    @ConfigProperty(name = "quantum.security.session.realm")
    Optional<String> sessionRealm = Optional.empty();

    // Delegated identity in the system realm: a token minted for the system realm adopts the
    // cross-tenant system data domain, so it must carry one of these roles. The default,
    // `system`, is the long-standing rule. A service that hosts a narrower control-plane identity
    // (a registry reader whose rows are then scoped by realm policies) names that role here too;
    // the data-domain adoption is unchanged, so the role's own policy rules still bound what it sees.
    @ConfigProperty(name = "quantum.security.system-realm.authority-roles", defaultValue = "system")
    List<String> systemRealmAuthorityRoles = List.of("system");

    // Remote principal source (quantum.auth.principal-source=remote): every verified bearer is
    // resolved by Auth for this application. Unset (or absent, as in unit tests) keeps the paths below.
    @Inject
    com.e2eq.framework.security.principal.RemotePrincipalSettings remotePrincipalSettings;

    @Inject
    com.e2eq.framework.security.runtime.RuleContext ruleContext;

    // Identity-mode resolution of service tokens v2 on the local credential path: the receiving
    // application's id and the binding store the resolver admits it through (as Auth's resolve does).
    @Inject
    jakarta.enterprise.inject.Instance<com.e2eq.framework.model.persistent.morphia.ReceivingApplication> receivingApplication;

    @Inject
    jakarta.enterprise.inject.Instance<com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory> accessDirectory;

    @Inject
    jakarta.enterprise.inject.Instance<com.e2eq.framework.model.securityrules.PrincipalContextPropertiesResolver> propertyResolvers;



    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // HERE FOR DOCUMENTATION ONLY
        // the context may be set in an upstream filter specifically in the PermissionPreFilter based upon
        // an annotation.  In this case determineResourceContext() as well determinePrincipalContext() should
        // consider if the context is already set and re use it.  If this causes issues with an old context being
        // left around, determine how to clear the context after the action is complete, vs, upfront here.
        //SecurityContext.clear();

        ResourceContext resourceContext;
        PrincipalContext principalContext;
        // Initialize per-request permission cache (if enabled)
        try {
            if (ruleContext != null) {
                com.e2eq.framework.security.runtime.RuleContext.initRequestCacheIfAbsent();
            }
        } catch (Throwable ignored) {}

        // we ignore the hello endpoint because it should never require authroization
        // and is used for heart beats, so we just let it go through
        if (requestContext.getUriInfo().getPath().contains("hello")) {
            return;
        }

        // Check if the endpoint is annotated with @PermitAll
        boolean isPermitAllEndPoint = isPermitAllEndpoint();
        if (isPermitAllEndPoint && Log.isDebugEnabled()) {
            Log.debugf("@PermitAll endpoint detected: %s - setting contexts but bypassing policy checks",
                requestContext.getUriInfo().getPath());
        }

        boolean isAuthenticatedEndPoint = isAuthenticatedEndpoint();
        if (isAuthenticatedEndPoint && Log.isDebugEnabled()) {
            Log.debugf("@Authenticated endpoint detected: %s - setting contexts but bypassing policy checks",
                requestContext.getUriInfo().getPath());
        }

        // Determine the resource and principal contexts
        resourceContext = determineResourceContext(requestContext);
        principalContext = determinePrincipalContext(requestContext);

        if (principalContext == null) {
            throw new IllegalStateException("Principal context came back null and should not be null");
        }
        if (principalContext.getRoles() != null
                && java.util.Arrays.asList(principalContext.getRoles()).contains(UserRealmRole.ROLE_EXTERNAL)
                && (isBypassDataScoping() || isServiceTokenMintRequest())) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity(apiError("EXTERNAL_OPERATION_REFUSED",
                            "External principals cannot bypass data scoping or mint service tokens"))
                    .build());
            return;
        }
        if (resourceContext != null && resourceContext.getDataDomain() == null) {
            resourceContext = resourceContext.withDataDomain(principalContext.getDataDomain());
        }

        // Always set the contexts - they may be needed by other parts of the application
        // even for @PermitAll endpoints. The policy checks will be bypassed elsewhere.
        SecurityContext.setPrincipalContext(principalContext);
        SecurityContext.setResourceContext(resourceContext);

        // Pre-authorization check: enforce policy rules before endpoint execution
        boolean skipPreAuth = isPermitAllEndPoint || isAuthenticatedEndPoint;

        // For system-level endpoints with bypassDataScoping=true, we trust Jakarta Security's
        // @RolesAllowed check and skip the policy rule evaluation entirely.
        // This avoids issues where rule matching depends on complex role resolution that may
        // differ between the permission check API and the actual request flow.
        if (!skipPreAuth && isBypassDataScoping()) {
            if (Log.isInfoEnabled()) {
                Log.infof("Bypassing policy rules for system operation: user=%s, area=%s, domain=%s, action=%s, " +
                          "roles=%s (bypassDataScoping=true, trusting @RolesAllowed)",
                    principalContext.getUserId(),
                    resourceContext.getArea(),
                    resourceContext.getFunctionalDomain(),
                    resourceContext.getAction(),
                    principalContext.getRoles() != null ? java.util.Arrays.toString(principalContext.getRoles()) : "[]");
            }
            skipPreAuth = true;
        }

        if (!skipPreAuth && enforcePreAuth) {
            enforcePreAuthorization(principalContext, resourceContext, requestContext);
        }

        // Note: @PermitAll endpoints bypass policy checks at the Jakarta Security level,
        // but we still set up the contexts here for consistency and potential use by other filters/components.
    }

        @Override
        public void filter(ContainerRequestContext requestContext, jakarta.ws.rs.container.ContainerResponseContext responseContext) throws IOException {
            // Clear ThreadLocal contexts after a response is produced
            SecurityContext.clear();
            // Clear per-request permission cache
            try {
                com.e2eq.framework.security.runtime.RuleContext.clearRequestCache();
            } catch (Throwable ignored) {}
        }


        public ResourceContext determineResourceContext (ContainerRequestContext requestContext) {
            // Always determine the resource context from the current request and overwrite any prior value
            // to avoid leaking/caching context across multiple requests within the same thread/test.
            ResourceContext rcontext = null;
            String area = null;
            String functionalDomain = null;
            String action = null;

            // @PermitAll endpoints don't require functional mapping
            if (isPermitAllEndpoint()) {
                rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
                SecurityContext.setResourceContext(rcontext);
                return rcontext;
            }

            // PRIORITY-BASED RESOLUTION (Model as Single Source of Truth)
            // Priority 1: Model @FunctionalMapping annotation (SINGLE SOURCE OF TRUTH)
            // Priority 2: Model bmFunctionalArea()/bmFunctionalDomain() methods (fallback)
            // Priority 3: JAX-RS method-level @FunctionalMapping annotation (rare overrides)
            // Priority 4: JAX-RS class-level @FunctionalMapping annotation (for non-BaseResource classes)
            // NO URL PARSING - Model or JAX-RS resource must define functional mapping

            try {
                if (resourceInfo != null) {
                    // Priority 1 & 2: Model annotation or methods (injected by ModelMappingExtractor)
                    String modelArea = (String) requestContext.getProperty("model.functional.area");
                    String modelDomain = (String) requestContext.getProperty("model.functional.domain");
                    if (modelArea != null && modelDomain != null) {
                        area = modelArea;
                        functionalDomain = modelDomain;
                        if (Log.isDebugEnabled()) {
                            Log.debugf("Using model mapping: area=%s, domain=%s", area, functionalDomain);
                        }
                    }

                    // Priority 3: JAX-RS method-level annotation
                    if (area == null) {
                        java.lang.reflect.Method rm = resourceInfo.getResourceMethod();
                        if (rm != null) {
                            com.e2eq.framework.annotations.FunctionalMapping methodMapping =
                                rm.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
                            if (methodMapping != null) {
                                area = methodMapping.area();
                                functionalDomain = methodMapping.domain();
                                if (Log.isDebugEnabled()) {
                                    Log.debugf("Using JAX-RS method @FunctionalMapping: area=%s, domain=%s", area, functionalDomain);
                                }
                            }
                        }
                    }

                    // Priority 4: JAX-RS class-level annotation
                    if (area == null && resourceInfo.getResourceClass() != null) {
                        Class<?> rc = resourceInfo.getResourceClass();
                        com.e2eq.framework.annotations.FunctionalMapping classMapping =
                            rc.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
                        if (classMapping != null) {
                            area = classMapping.area();
                            functionalDomain = classMapping.domain();
                            if (Log.isDebugEnabled()) {
                                Log.debugf("Using JAX-RS class @FunctionalMapping: area=%s, domain=%s", area, functionalDomain);
                            }
                        }
                    }

                    // Fallback to bmFunctionalArea() and bmFunctionalDomain() if still null
                    if (area == null || functionalDomain == null) {
                        try {
                            Class<?> resourceClass = resourceInfo.getResourceClass();
                            if (resourceClass != null) {
                                Class<?> modelClass = findModelClass(resourceClass);
                                if (modelClass != null && com.e2eq.framework.model.persistent.base.UnversionedBaseModel.class.isAssignableFrom(modelClass)) {
                                    com.e2eq.framework.model.persistent.base.UnversionedBaseModel instance = (com.e2eq.framework.model.persistent.base.UnversionedBaseModel) modelClass.getDeclaredConstructor().newInstance();
                                    if (area == null) area = instance.bmFunctionalArea();
                                    if (functionalDomain == null) functionalDomain = instance.bmFunctionalDomain();
                                    if (Log.isDebugEnabled()) {
                                        Log.debugf("Using fallback bmFunctional methods: area=%s, domain=%s", area, functionalDomain);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignore fallback failures
                        }
                    }

                    // Determine action: 1) annotation, 2) URL keywords, 3) HTTP method
                    action = getAnnotatedAction();

                    // Build context if we found area/domain
                    if (area != null && functionalDomain != null) {
                        if (action == null) {
                            String path = requestContext.getUriInfo().getPath();
                            action = inferActionFromUrlKeywords(path);
                        }

                        if (action == null) {
                            action = inferActionFromHttpMethod(requestContext.getMethod());
                        }

                        if ("list".equalsIgnoreCase(action)) {
                            action = "LIST";
                        }
                        rcontext = new ResourceContext.Builder()
                                .withApplicationId(currentApplicationId())
                                .withArea(area)
                                .withFunctionalDomain(functionalDomain)
                                .withAction(action)
                                .build();
                        SecurityContext.setResourceContext(rcontext);
                        Log.infof("Resource Context set: area=%s, domain=%s, action=%s",
                                area, functionalDomain, action);
                        return rcontext;
                    }
                } else {
                    Log.infof("Resource Info is null for request: %s", requestContext.getUriInfo().getPath());
                }
            } catch (Exception ex) {
                Log.errorf(ex, "Resource context resolution failed");
            }

            // No mapping found - error
            String path = requestContext.getUriInfo().getPath();
            Log.errorf("Setting default Anonymous Context for ResourceContext, No functional mapping annotations found for path: %s. Add @FunctionalMapping to model class, implement bmFunctionalArea()/bmFunctionalDomain() methods, or add @FunctionalMapping to JAX-RS resource.", path);
            rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
            SecurityContext.setResourceContext(rcontext);
            return rcontext;
        }

    private String inferActionFromHttpMethod(String http) {
        if (http == null) return "*";
        return switch (http.toUpperCase(Locale.ROOT)) {
            case "GET" -> "VIEW";
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> http;
        };
    }

    private String inferActionFromUrlKeywords(String path) {
        if (path == null) return null;
        String lowerPath = path.toLowerCase();
        if (lowerPath.contains("/list")) {
            return "LIST";
        } else if (lowerPath.contains("/create")) {
            return "CREATE";
        } else if (lowerPath.contains("/delete")) {
            return "DELETE";
        } else if (lowerPath.contains("/update")) {
            return "UPDATE";
        }
        return null;
    }

    private Class<?> findModelClass(Class<?> resourceClass) {
        Class<?> clazz = resourceClass;
        while (clazz != null && clazz != Object.class) {
            java.lang.reflect.Type genericSuperclass = clazz.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) genericSuperclass;
                if (com.e2eq.framework.rest.core.BaseResource.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                    java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                        return (Class<?>) typeArgs[0];
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Extracts the action from @FunctionalAction annotation if present on the resource method.
     * @return the action value from annotation, or null if not present
     */
    private String getAnnotatedAction() {
        try {
            if (resourceInfo != null && resourceInfo.getResourceMethod() != null) {
                com.e2eq.framework.annotations.FunctionalAction fa =
                    resourceInfo.getResourceMethod().getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class);
                if (fa != null) {
                    return fa.value();
                }
            }
        } catch (Exception ex) {
            Log.debugf("Failed to extract @FunctionalAction annotation: %s", ex.toString());
        }
        return null;
    }

    /**
     * Checks if the current endpoint has bypassDataScoping=true on @FunctionalAction.
     * When true, the endpoint is a system-level operation that does not operate on
     * data entities with dataDomain scoping - SCOPED constraints should be ignored.
     *
     * @return true if the method is annotated with @FunctionalAction(bypassDataScoping=true)
     */
    private boolean isBypassDataScoping() {
        try {
            if (resourceInfo != null && resourceInfo.getResourceMethod() != null) {
                com.e2eq.framework.annotations.FunctionalAction fa =
                    resourceInfo.getResourceMethod().getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class);
                if (fa != null) {
                    return fa.bypassDataScoping();
                }
            }
        } catch (Exception ex) {
            Log.debugf("Failed to check bypassDataScoping attribute: %s", ex.toString());
        }
        return false;
    }

    /**
     * The shared credential path (embedded mode). Built from this filter's own collaborators so
     * the filter and {@code quantum-auth-service}'s resolution endpoint run one implementation.
     */
    com.e2eq.framework.security.principal.PrincipalContextResolver resolver() {
        return new com.e2eq.framework.security.principal.PrincipalContextResolver(
                systemDirectory, realmMembershipService, credentialRepo, identityRoleResolver,
                securityUtils, envConfigUtils,
                new com.e2eq.framework.security.principal.ImpersonationScriptEvaluator(
                        scriptingEnabled, scriptingAllowAllAccess, scriptingTimeoutMillis, scriptingMaxStatements));
    }

    /**
     * The identity-mode resolver for service tokens v2: {@link #resolver()} plus this application's
     * binding store, so admission, realm and tenant selection run exactly as Auth's
     * {@code POST /v1/auth/principal-context/resolve} runs them.
     */
    com.e2eq.framework.security.principal.PrincipalContextResolver identityResolver() {
        com.e2eq.framework.model.persistent.morphia.ApplicationAccessDirectory directory =
                accessDirectory != null && accessDirectory.isResolvable() ? accessDirectory.get() : null;
        return new com.e2eq.framework.security.principal.PrincipalContextResolver(
                systemDirectory, realmMembershipService, credentialRepo, identityRoleResolver,
                securityUtils, envConfigUtils,
                new com.e2eq.framework.security.principal.ImpersonationScriptEvaluator(
                        scriptingEnabled, scriptingAllowAllAccess, scriptingTimeoutMillis, scriptingMaxStatements),
                directory);
    }

    /**
     * This application's id ({@code quantum.security.session.application-id}, else
     * {@code quantum.application-registration.application-id}), never a token claim; null when the
     * receiver names none (an application-agnostic service such as Auth itself).
     */
    String receivingApplicationId() {
        if (receivingApplication != null && receivingApplication.isResolvable()) {
            String configuredApplication = receivingApplication.get().applicationId();
            if (configuredApplication != null) {
                return configuredApplication;
            }
        }
        return configured(sessionApplicationId);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean APPLICATION_AGNOSTIC_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Once per process: a v2 service token reached a receiver that names no application, so no
     * application admission applies to it (correct for Auth itself; anywhere else, configure the
     * receiving application). Not refused: Auth's own endpoints rely on this path.
     */
    static boolean warnApplicationAgnosticServiceToken() {
        if (!APPLICATION_AGNOSTIC_WARNED.compareAndSet(false, true)) {
            return false;
        }
        Log.warnf("A service token v2 reached a receiver that configures no application (%s or %s): it resolves "
                        + "from its credential and realm grants without application admission. Configure the "
                        + "receiving application unless this is an application-agnostic service such as Auth.",
                com.e2eq.framework.model.persistent.morphia.ReceivingApplication.SESSION_APPLICATION_ID,
                com.e2eq.framework.model.persistent.morphia.ReceivingApplication.REGISTRATION_APPLICATION_ID);
        return true;
    }

    protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
        RequestAuthorizationState requestState = RequestAuthorizationState.from(routingContext);
        if (requestState != null) requestState.beginAdmission();
        if (remotePrincipalSource()) {
            PrincipalContext remote = remotePrincipalContext(requestContext, requestState);
            if (remote != null) {
                return remote;
            }
        }
        validateContextInputs(requestContext);

        String tenantId = requestContext.getHeaderString("X-Tenant-Id");
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        String realm = sessionSelection != null && sessionSelection.enabled()
                ? sessionSelection.realm(routingContext, securityIdentity, requestContext.getHeaderString("X-Realm"), configured(sessionRealm))
                : sessionRealmSelection(requestContext.getHeaderString("X-Realm"), configured(sessionRealm), isUserPrincipal());

        if (localCredentialPath(authHeader)) {
            boolean identityOnlyService = identityOnlyServiceToken();
            if (identityOnlyService) {
                admitIdentityOnlyServiceToken(requestContext);
            }
            String receivingApplicationId = identityOnlyService ? receivingApplicationId() : null;
            if (identityOnlyService && receivingApplicationId == null) {
                warnApplicationAgnosticServiceToken();
            }
            com.e2eq.framework.security.principal.PrincipalContextResolution resolution = receivingApplicationId != null
                    ? resolveIdentityOnlyServiceToken(requestContext, realm, tenantId, receivingApplicationId)
                    : resolver().resolve(localCredentialRequest(requestContext, realm, tenantId, identityOnlyService));
            PrincipalContext completed = applyCustomProperties(
                    requestContext, resolution.context(), resolution.callerCredential());
            if (requestState != null) requestState.complete(
                    ResolvedAuthorizationContext.from(claimString("iss"), claimString("sub"), completed));
            return completed;
        }

        // Build context and track credentials for property resolvers
        ContextBuildResult buildResult = buildBaseContextWithCredentials(authHeader,
                baseRealmOverride(realm, trustTokenClaims, impersonationRequested(requestContext),
                        envConfigUtils.getSystemRealm()));
        PrincipalContext baseContext = buildResult.context;
        CredentialUserIdPassword credentials = buildResult.credentials;

        PrincipalContext contextWithImpersonation = handleImpersonation(requestContext, baseContext);
        PrincipalContext contextWithRealm = applyRealmOverride(contextWithImpersonation, realm);
        requireSessionApplicationAdmitted(contextWithRealm);
        // The signed tenant claim names a tenant of the realm the token was
        // issued for. It applies only while the request stays in that realm;
        // after an X-Realm switch the caller must select a tenant explicitly.
        String tokenTenantId = buildResult.tokenRealm != null
                && buildResult.tokenRealm.equals(contextWithRealm.getDefaultRealm())
                ? buildResult.tokenTenantId
                : null;
        PrincipalContext contextWithTenant = applyTenantSelection(contextWithRealm, tenantId, tokenTenantId);

        // Apply custom property resolvers
        PrincipalContext completed = applyCustomProperties(requestContext,
                isUserPrincipal() ? resolver().finalizeUserPrincipal(contextWithTenant) : contextWithTenant, credentials);
        if (requestState != null) requestState.complete(
                ResolvedAuthorizationContext.from(claimString("iss"), claimString("sub"), completed));
        return completed;
    }

    private boolean remotePrincipalSource() {
        return remotePrincipalSettings != null && remotePrincipalSettings.remote();
    }

    /**
     * Remote mode ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 3): the bearer was verified by
     * signature; Auth resolves who it is for this application, with the request's realm, tenant,
     * impersonation and acting-on-behalf-of selections. Token claims beyond {@code iss} and
     * {@code sub} are not read, and Auth has already applied the realm switch, application
     * admission and tenant selection, so none runs here. A refusal or an unreachable Auth fails
     * the request ({@link com.e2eq.framework.security.principal.RemotePrincipalResolutionException});
     * there is no fallback to the claims or local credential paths. A platform operation
     * ({@link #isPlatformTenantExemptRequest()}) asks with tenant selection optional, as the local
     * paths exempt it. Returns null for a request without a verified JWT (anonymous), which keeps
     * its usual path.
     */
    private PrincipalContext remotePrincipalContext(ContainerRequestContext requestContext,
                                                    RequestAuthorizationState requestState) {
        JsonWebToken token = currentJwt();
        if (token == null) {
            return null;
        }
        // Every verified JWT, however it was carried (header or cookie), is resolved remotely and
        // presented by its own encoding; none falls back to the local paths.
        String issuer = claimString("iss");
        String subject = claimString("sub");
        PrincipalContext resolved = RemotePrincipals.resolve(remotePrincipalSettings, routingContext, token,
                requestContext::getHeaderString, isPlatformTenantExemptRequest());
        PrincipalContext completed = applyCustomProperties(requestContext, resolved, null);
        if (requestState != null) requestState.complete(
                ResolvedAuthorizationContext.from(issuer, subject, completed));
        return completed;
    }

    /**
     * A user JWT, or an identity-only service token, verified locally against this application's
     * credential store: resolved by {@link #resolver()}. Legacy service tokens (role-bearing, no
     * {@code jti}) keep the service-claims path until rollout step 4.
     */
    boolean localCredentialPath(String authHeader) {
        if (authHeader == null || currentJwt() == null) {
            return false;
        }
        if (identityOnlyServiceToken()) {
            // Never the claims paths: an identity-only token names no roles, realm or tenant, and
            // the service-claims path would run it in the system realm.
            return true;
        }
        return !trustTokenClaims && !serviceToken();
    }

    private boolean serviceToken() {
        return com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                .equalsIgnoreCase(claimString(
                        com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM));
    }

    /**
     * A service token v2 ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 4), recognised from the
     * token alone: {@code token_type=service} whose {@code jti} is its subject (the credential id
     * is the credential's subject). A role-bearing service token's {@code jti}, when the signer
     * adds one, is a random token id, never the subject. Such a token is identity only, so it
     * resolves like a user, from its credential, home realm, tenant and grants.
     */
    boolean identityOnlyServiceToken() {
        String jti = claimString("jti");
        return serviceToken() && jti != null && jti.equals(claimString("sub"));
    }

    /**
     * Admits an identity-only service token before resolution, fail closed: this application must
     * hold its credential (otherwise only Auth can resolve it: run the receiver in remote mode), the
     * credential must be the active service credential the token names and record a kind, and the
     * request may not impersonate or act on behalf of an identity. Delegation (a {@code DELEGATING}
     * credential acting for an identity) is resolved only by Auth's identity-mode resolution
     * ({@code POST /v1/auth/principal-context/resolve}, which remote receivers call): it checks the
     * delegation scope and returns the acted-for identity. The local credential path cannot, so it
     * refuses the headers rather than run as the service with an unchecked acted-for marker. Its
     * roles come from the credential and its grants alone: at a receiver that names its application
     * through an identity-mode request ({@link #resolveIdentityOnlyServiceToken}); at an
     * application-agnostic receiver (no application configured, such as Auth itself, where no
     * application grant can apply) through {@link #localCredentialRequest} with every token claim
     * beyond the identity dropped. The credential is read by the resolver's own authentication
     * lookup ({@link com.e2eq.framework.security.principal.PrincipalContextResolver#credentialForSubject}),
     * the one read every bearer gets before a principal exists; this filter has no lookup of its own.
     */
    void admitIdentityOnlyServiceToken(ContainerRequestContext requestContext) {
        String sub = claimString("sub");
        if (trustTokenClaims || credentialRepo == null || sub == null) {
            throw new jakarta.ws.rs.NotAuthorizedException(
                    "An identity-only service token is resolved by Auth; this receiver must resolve principals "
                            + "through Auth (remote mode)", "Bearer");
        }
        CredentialUserIdPassword credential = resolver().credentialForSubject(sub)
                .orElseThrow(() -> new jakarta.ws.rs.NotAuthorizedException(
                        "No service credential for this token in this application's credential store; resolve "
                                + "it through Auth (remote mode)", "Bearer"));
        com.e2eq.framework.model.persistent.base.ActiveStatus status = credential.getActiveStatus();
        if (credential.getServiceKind() == null
                || !sub.equals(credential.getSubject())
                || !claimString("jti").equals(credential.getSubject())
                || status == com.e2eq.framework.model.persistent.base.ActiveStatus.INACTIVE
                || status == com.e2eq.framework.model.persistent.base.ActiveStatus.DELETED) {
            throw new jakarta.ws.rs.NotAuthorizedException(
                    "The service credential for this token is not an active identity-only service credential", "Bearer");
        }
        if (impersonationRequested(requestContext)) {
            throw new jakarta.ws.rs.ForbiddenException("A service token never impersonates; a delegating service "
                    + "acts on behalf of an identity instead");
        }
        boolean acting = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_SUBJECT) != null
                || requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_USER_ID) != null;
        if (acting && credential.getServiceKind() != com.e2eq.framework.model.security.ServiceKind.DELEGATING) {
            throw new jakarta.ws.rs.ForbiddenException("DELEGATION_NOT_ALLOWED: an application service token never "
                    + "acts on behalf of another identity");
        }
        if (acting) {
            throw new jakarta.ws.rs.ForbiddenException("DELEGATION_NOT_ALLOWED: a delegating service token acts on "
                    + "behalf of an identity only at receivers that resolve through Auth (remote mode); the local "
                    + "credential path does not resolve delegation");
        }
    }

    /**
     * A service token v2 at a receiver that names its application resolves in identity mode, the
     * same request Auth's resolve operation builds for a remote receiver: only the identity
     * ({@code sub}) is taken from the token; application admission (bindings, then the realm
     * assignment's grant), the identity's realm and tenant selection and the SERVICE scope follow
     * from the credential and its grants. Impersonation and acting-on-behalf-of were refused by
     * {@link #admitIdentityOnlyServiceToken}. A refusal is typed, with the code's status.
     */
    private com.e2eq.framework.security.principal.PrincipalContextResolution resolveIdentityOnlyServiceToken(
            ContainerRequestContext requestContext, String realm, String tenantId, String applicationId) {
        try {
            return identityResolver().resolve(identityOnlyServiceRequest(realm, tenantId, applicationId));
        } catch (com.e2eq.framework.security.principal.PrincipalResolutionException refused) {
            throw com.e2eq.framework.security.principal.RemotePrincipalResolutionException.of(
                    refused.error(), refused.getMessage());
        }
    }

    com.e2eq.framework.security.principal.PrincipalContextRequest identityOnlyServiceRequest(
            String realm, String tenantId, String applicationId) {
        return com.e2eq.framework.security.principal.PrincipalContextRequest
                .identity(claimString("sub"), null)
                .applicationId(applicationId)
                .realm(realm)
                .tenantId(tenantId)
                .tenantSelectionOptional(isPlatformTenantExemptRequest())
                .build();
    }

    private com.e2eq.framework.security.principal.PrincipalContextRequest localCredentialRequest(
            ContainerRequestContext requestContext, String realm, String tenantId, boolean identityOnlyService) {
        String sub = jwt.getClaim("sub");
        // An identity-only service token contributes no roles, realm or tenant of its own: none of
        // them is in the token, and the request identity's default roles must not stand in for grants.
        var claims = new com.e2eq.framework.security.principal.PrincipalContextRequest.SignedClaims(
                identityOnlyService ? null : claimString("realm"),
                identityOnlyService ? null : claimString("tenantId"),
                identityOnlyService || foreignApplicationToken(tokenApplicationId()) ? null : securityIdentity,
                !identityOnlyService && foreignApplicationToken(tokenApplicationId()),
                requestContext.getHeaderString("X-Realm"),
                requestContext.getHeaderString(
                        com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.ORIGINAL_SUBJECT),
                requestContext.getHeaderString(
                        com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.ORIGINAL_USER_ID),
                isPlatformTenantExemptRequest());
        var request = com.e2eq.framework.security.principal.PrincipalContextRequest
                .signedTokenClaims(sub, claims)
                // A v2 service token names no application: never its azp. Here (no receiving
                // application configured) it resolves for no application.
                .applicationId(identityOnlyService ? null : currentApplicationId())
                .realm(baseRealmOverride(realm, trustTokenClaims, impersonationRequested(requestContext),
                        envConfigUtils.getSystemRealm()))
                .tenantId(tenantId)
                .impersonate(requestContext.getHeaderString("X-Impersonate-Subject"),
                        requestContext.getHeaderString("X-Impersonate-UserId"))
                .actingOnBehalfOf(
                        requestContext.getHeaderString(
                                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_SUBJECT),
                        requestContext.getHeaderString(
                                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_USER_ID));
        for (String claim : List.of("username", "preferred_username", "email")) {
            Object raw = jwt.getClaim(claim);
            if (raw != null && !String.valueOf(raw).trim().isEmpty()) {
                request.userIdCandidate(String.valueOf(raw).trim());
            }
        }
        return request.build();
    }

    /**
     * The realm a request selects: an explicit X-Realm, otherwise the configured session realm for a
     * user principal, otherwise none (the principal's own realm applies).
     */
    static String sessionRealmSelection(String headerRealm, String configuredRealm, boolean userPrincipal) {
        if (headerRealm != null && !headerRealm.isBlank()) {
            return headerRealm;
        }
        return userPrincipal && configuredRealm != null ? configuredRealm : headerRealm;
    }

    private static String configured(Optional<String> value) {
        if (value == null) {
            return null;
        }
        return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }

    /** A verified, non-service JWT or another authenticated, non-anonymous identity. */
    private boolean isUserPrincipal() {
        if (currentJwt() != null) {
            return !com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                    .equalsIgnoreCase(claimString(
                            com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM));
        }
        return securityIdentity != null && !securityIdentity.isAnonymous();
    }

    private static boolean impersonationRequested(ContainerRequestContext requestContext) {
        return requestContext.getHeaderString("X-Impersonate-Subject") != null
                || requestContext.getHeaderString("X-Impersonate-UserId") != null;
    }

    /**
     * The X-Realm the base (token holder's) context is built in. With delegated
     * token claims, an impersonation request does not switch the token holder
     * into X-Realm: the request runs as the impersonated credential in its own
     * realm, and X-Realm only names the realm the impersonateFilterScript is asked
     * about, with the impersonator's realm access checked against their stored
     * credential in {@link #handleImpersonation}. The system realm is never
     * dropped here, so the delegated path keeps refusing a switch into it.
     */
    static String baseRealmOverride(String realm, boolean trustTokenClaims, boolean impersonating, String systemRealm) {
        if (trustTokenClaims && impersonating && realm != null && !realm.equalsIgnoreCase(systemRealm)) {
            return null;
        }
        return realm;
    }

    /**
     * Whether a delegated token's roles admit it to the system realm. `authorityRoles` is
     * `quantum.security.system-realm.authority-roles` (default `system`); names are compared
     * ignoring case and blank entries are ignored, so an empty or malformed setting never
     * widens the gate: with nothing valid configured only `system` admits.
     */
    static boolean hasSystemRealmAuthority(String[] roles, List<String> authorityRoles) {
        if (roles == null || roles.length == 0) {
            return false;
        }
        List<String> accepted = authorityRoles == null
                ? List.of()
                : authorityRoles.stream().filter(r -> r != null && !r.isBlank()).map(String::trim).toList();
        if (accepted.isEmpty()) {
            accepted = List.of("system");
        }
        for (String role : roles) {
            if (role == null) {
                continue;
            }
            for (String allowed : accepted) {
                if (allowed.equalsIgnoreCase(role.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Result holder for context building that includes both the context and credentials.
     */
    private static class ContextBuildResult {
        final PrincipalContext context;
        final CredentialUserIdPassword credentials;
        /** Tenant named by the verified token's {@code tenantId} claim, or null. */
        final String tokenTenantId;
        /** Realm the verified token was issued for (before any X-Realm switch), or null. */
        final String tokenRealm;

        ContextBuildResult(PrincipalContext context, CredentialUserIdPassword credentials) {
            this(context, credentials, null, null);
        }

        ContextBuildResult(PrincipalContext context, CredentialUserIdPassword credentials,
                           String tokenTenantId, String tokenRealm) {
            this.context = context;
            this.credentials = credentials;
            this.tokenTenantId = tokenTenantId;
            this.tokenRealm = tokenRealm;
        }
    }

    private ContextBuildResult buildBaseContextWithCredentials(String authHeader, String realm) {
        if (authHeader != null && currentJwt() != null) {
            return buildJwtContextWithCredentials(authHeader, realm);
        } else if (securityIdentity != null && !securityIdentity.isAnonymous()) {
            return buildIdentityContextWithCredentials(realm);
        } else {
            return new ContextBuildResult(buildAnonymousContext(realm), null);
        }
    }

    private PrincipalContext buildAnonymousContext(String realm) {
        String contextRealm = (realm != null) ? realm : envConfigUtils.getSystemRealm();
        return new PrincipalContext.Builder()
                .withDefaultRealm(contextRealm)
                .withDataDomain(securityUtils.getSystemDataDomain())
                .withUserId(envConfigUtils.getAnonymousUserId())
                .withRoles(new String[]{"ANONYMOUS"})
                .withScope("systemGenerated")
                .build();
    }

    private ContextBuildResult buildIdentityContextWithCredentials(String realmOverride) {
        String principalName = securityIdentity.getPrincipal() != null ?
            securityIdentity.getPrincipal().getName() : envConfigUtils.getAnonymousUserId();

        Log.debugf("buildIdentityContextWithCredentials: principalName=%s, realmOverride=%s", principalName, realmOverride);

        // Credentials are looked up from the configured system realm (global credential store)
        Optional<CredentialUserIdPassword> ocreds = credentialRepo.findByUserId(principalName, envConfigUtils.getSystemRealm(), true);

        if (ocreds.isPresent()) {
            CredentialUserIdPassword creds = ocreds.get();

            // Keep the base context on the credential's home realm. X-Realm is applied later by
            // applyRealmOverride(), but we still need the target realm here for role resolution.
            String credentialRealm = creds.getDomainContext().getDefaultRealm();
            String roleLookupRealm = credentialRealm;
            Log.debugf("buildIdentityContextWithCredentials: credential found for userId=%s, domainContext.defaultRealm=%s",
                creds.getUserId(), credentialRealm);

            if (realmOverride != null) {
                validateRealmAccess(creds, realmOverride);
                roleLookupRealm = realmOverride;
                Log.debugf("buildIdentityContextWithCredentials: X-Realm accepted for role lookup, targetRealm=%s", roleLookupRealm);
            }

            DataDomain dataDomain = creds.getDomainContext().toDataDomain(creds.getUserId());
            Log.debugf("buildIdentityContextWithCredentials: resolving roles with roleLookupRealm=%s, subject=%s",
                roleLookupRealm, creds.getSubject());
            String[] roles = resolveEffectiveRoles(
                    securityIdentity, creds, credentialRealm, roleLookupRealm);

            PrincipalContext context = new PrincipalContext.Builder()
                    .withDefaultRealm(credentialRealm)
                    .withApplicationId(currentApplicationId())
                    .withDomainContext(creds.getDomainContext())
                    .withDataDomain(dataDomain)
                    .withUserId(creds.getUserId())
                    .withRoles(roles)
                    .withScope("AUTHENTICATED")
                    .withDataDomainPolicy(creds.getDataDomainPolicy())
                    .build();
            return new ContextBuildResult(context, creds);
        }
        throw new jakarta.ws.rs.ForbiddenException(
                "Authenticated identity is not mapped to a Quantum credential");
    }

    private ContextBuildResult buildJwtContextWithCredentials(String authHeader, String realmOverride) {
        String token = authHeader.substring(AUTHENTICATION_SCHEME.length()).trim();
        String sub = jwt.getClaim("sub");

        Log.debugf("buildJwtContextWithCredentials: sub=%s, realmOverride=%s", sub, realmOverride);

        if (sub == null) {
            throw new IllegalStateException("sub attribute not provided but is required in token claims");
        }
        if (com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                .equalsIgnoreCase(claimString(
                        com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM))) {
            return buildServiceClaimsContext(sub, realmOverride);
        }

        // A delegated provider owns identity data. Resolve its provider-neutral claims
        // before touching any local credential/profile/realm repository.
        if (trustTokenClaims) {
            AuthProvider selectedProvider = authProviderFactory.getAuthProvider();
            if (!(selectedProvider instanceof ClaimsAuthProvider claimsProvider)) {
                throw new jakarta.ws.rs.ForbiddenException(
                        "Configured auth provider does not support delegated token claims: "
                                + selectedProvider.getName());
            }
            ProviderClaims providerClaims = claimsProvider.validateTokenToClaims(token);
            return buildDelegatedClaimsContext(providerClaims, realmOverride);
        }

        throw new IllegalStateException(
                "A locally verified user token resolves through PrincipalContextResolver");
    }

    /**
     * Builds a PrincipalContext purely from claims validated by the configured provider, with no local
     * credential/profile lookup. Used when {@code quantum.auth.trust-token-claims=true}. Identity comes
     * from userId/preferred_username/username (or the subject), and roles come from the provider claims.
     */
    private ContextBuildResult buildDelegatedClaimsContext(ProviderClaims claims, String realmOverride) {
        Objects.requireNonNull(claims, "Delegated provider claims are required");
        String sub = claims.getSubject();
        String userId = providerClaim(claims, "userId");
        if (userId == null) userId = providerClaim(claims, "preferred_username");
        if (userId == null) userId = claims.getUsername();
        if (userId == null) userId = providerClaim(claims, "username");
        if (userId == null) userId = sub;

        String systemRealm = envConfigUtils.getSystemRealm();
        String signedRealm = providerClaim(claims, "realm");
        if (signedRealm == null) {
            throw new jakarta.ws.rs.ForbiddenException(
                    "Trusted-issuer token is missing the required realm claim");
        }
        // The issuer signs the credential's realm boundary into the token as
        // realmRegEx ("*" = all realms, otherwise a regex). An X-Realm switch is
        // honored only inside that signed boundary, and never INTO the system
        // realm -- system authority requires logging in against it directly.
        boolean realmSwitched = false;
        String contextRealm = signedRealm;
        if (realmOverride != null && !realmOverride.isBlank()
                && !realmOverride.equalsIgnoreCase(signedRealm)) {
            String realmBoundary = providerClaim(claims, "realmRegEx");
            boolean withinBoundary = false;
            if (realmBoundary != null && !realmOverride.equalsIgnoreCase(systemRealm)) {
                if ("*".equals(realmBoundary)) {
                    withinBoundary = true;
                } else {
                    try {
                        withinBoundary = realmOverride.matches(realmBoundary);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        throw new jakarta.ws.rs.ForbiddenException(
                                "Signed realm boundary is not a valid pattern");
                    }
                }
            }
            if (!withinBoundary) {
                throw new jakarta.ws.rs.ForbiddenException(
                        "X-Realm does not match the realm authorized by the token");
            }
            contextRealm = realmOverride;
            realmSwitched = true;
        }
        // A token minted for another application carries that application's roles. In an
        // application session they are re-derived like a realm switch (flat credential roles
        // that the token also names, plus the realm assignment's roles), never adopted.
        String[] roles = realmSwitched || foreignApplicationToken(providerClaim(claims, "azp"))
                ? delegatedRolesAfterRealmSwitch(claims.getIssuer(), sub, userId, claims.getTokenRoles(), contextRealm)
                : claims.getTokenRoles().toArray(String[]::new);

        Log.infof("Delegated identity: principal from trusted-issuer token claims (iss=%s, sub=%s, userId=%s, realm=%s, roles=%s)",
                claims.getIssuer(), sub, userId, contextRealm, java.util.Arrays.toString(roles));

        // A delegated principal adopts the DATA DOMAIN of the tenant realm it operates in
        // (from the realm's registered DomainContext), not the cross-tenant system domain.
        // The system domain is reserved for the system realm / system admins — never a silent
        // fallback for an ordinary tenant user. Gated so services opt in explicitly.
        DataDomain dataDomain;
        if (contextRealm.equalsIgnoreCase(systemRealm)) {
            if (!hasSystemRealmAuthority(roles, systemRealmAuthorityRoles)) {
                throw new jakarta.ws.rs.ForbiddenException(
                        "The system realm requires system authority in the signed token");
            }
            dataDomain = securityUtils.getSystemDataDomain();
        } else {
            String tenantId = providerClaim(claims, "tenantId");
            String orgRefName = providerClaim(claims, "orgRefName");
            String accountNum = providerClaim(claims, "accountNum");
            // Token data-domain claims describe the LOGIN realm; a switched
            // realm's data domain must come from the realm catalog below.
            if (!realmSwitched && tenantId != null && orgRefName != null && accountNum != null) {
                dataDomain = new DataDomain(orgRefName, accountNum, tenantId, 0, userId);
            } else {
                // Older trusted tokens may not carry the full data-domain projection. Resolve
                // it through the deployment-mode SystemDirectory, but never substitute the
                // system domain when the tenant catalog is unavailable.
                Optional<Realm> realmOpt = systemDirectory.findRealmByRefName(contextRealm);
                if (realmOpt.isEmpty() || realmOpt.get().getDomainContext() == null) {
                    throw new jakarta.ws.rs.ForbiddenException(
                            "Trusted-issuer token is missing tenant data-domain claims for realm '"
                                    + contextRealm + "'");
                }
                dataDomain = realmOpt.get().getDomainContext().toDataDomain(userId);
            }
        }

        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm(contextRealm)
                .withApplicationId(configured(sessionApplicationId) != null
                        ? configured(sessionApplicationId)
                        : providerClaim(claims, "azp"))
                .withDataDomain(dataDomain)
                .withUserId(userId)
                .withRoles(roles)
                .withScope("AUTHENTICATED")
                .build();
        return new ContextBuildResult(context, null, providerClaim(claims, "tenantId"), signedRealm);
    }

    private static String providerClaim(ProviderClaims claims, String name) {
        Object value = claims.getAttributes().get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Build the authenticated calling-service context. The service token does
     * not carry a tenant/application resource binding; X-Realm remains request
     * context and user delegation is applied separately after this step.
     */
    private ContextBuildResult buildServiceClaimsContext(String sub, String realmOverride) {
        String contextRealm = realmOverride == null || realmOverride.isBlank()
                ? envConfigUtils.getSystemRealm()
                : realmOverride.trim();
        String serviceUserId = claimString("userId");
        if (serviceUserId == null) {
            serviceUserId = sub;
        }
        String[] roles = securityIdentity == null
                ? new String[0]
                : securityIdentity.getRoles().toArray(String[]::new);

        DataDomain dataDomain;
        if (contextRealm.equalsIgnoreCase(envConfigUtils.getSystemRealm())) {
            dataDomain = securityUtils.getSystemDataDomain();
        } else {
            Realm realm = systemDirectory.findRealmByRefName(contextRealm)
                    .filter(candidate -> candidate.getDomainContext() != null)
                    .orElseThrow(() -> new jakarta.ws.rs.ForbiddenException(
                            "Delegated service request names an unregistered realm: " + contextRealm));
            dataDomain = realm.getDomainContext().toDataDomain(serviceUserId);
        }

        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm(contextRealm)
                .withApplicationId(currentApplicationId())
                .withDataDomain(dataDomain)
                .withUserId(serviceUserId)
                .withRoles(roles)
                .withScope(PrincipalContext.SCOPE_SERVICE)
                .build();
        return new ContextBuildResult(context, null);
    }

    /** Returns the given JWT claim as a non-blank String, or null. */
    private String claimString(String name) {
        JsonWebToken currentJwt = currentJwt();
        if (currentJwt == null) {
            return null;
        }
        Object v = currentJwt.getClaim(name);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    String currentApplicationId() {
        String configuredApplication = configured(sessionApplicationId);
        if (configuredApplication != null && isUserPrincipal()) {
            return configuredApplication;
        }
        return tokenApplicationId();
    }

    /**
     * In an application session, a token minted for another application says nothing about this
     * application: the user must be admitted to it in the session realm by the one admission rule,
     * {@code PrincipalContextResolver#applicationAdmitted} (the realm's own application, or granted
     * on the realm assignment or by the credential's applicationRegEx).
     */
    void requireSessionApplicationAdmitted(PrincipalContext context) {
        if (!isUserPrincipal() || !foreignApplicationToken(tokenApplicationId())) {
            return;
        }
        resolver().requireSessionApplicationAdmitted(context, !trustTokenClaims);
    }

    /** True when an application session is configured and the token was minted for another application. */
    boolean foreignApplicationToken(String tokenApplication) {
        String configuredApplication = configured(sessionApplicationId);
        return configuredApplication != null && !configuredApplication.equalsIgnoreCase(tokenApplication);
    }

    private String tokenApplicationId() {
        String tokenApplication = claimString("azp");
        if (tokenApplication != null) {
            return tokenApplication;
        }
        if (securityIdentity == null) {
            return null;
        }
        Object value = securityIdentity.getAttribute("azp");
        if (value == null) {
            return null;
        }
        String applicationId = String.valueOf(value).trim();
        return applicationId.isEmpty() ? null : applicationId;
    }

    /**
     * The injected JWT is a request-scoped proxy and throws when Quarkus
     * authenticates the request with another mechanism (for example,
     * {@code @TestSecurity}). Inspect the established identity before touching
     * that proxy.
     */
    private JsonWebToken currentJwt() {
        if (securityIdentity == null || securityIdentity.getPrincipal() == null
                || !(securityIdentity.getPrincipal() instanceof JsonWebToken)) {
            return null;
        }
        return (JsonWebToken) securityIdentity.getPrincipal();
    }

    /**
     * Applies custom properties from PrincipalContextPropertiesResolver implementations.
     */
    private PrincipalContext applyCustomProperties(
            ContainerRequestContext requestContext,
            PrincipalContext context,
            CredentialUserIdPassword credentials) {

        if (propertyResolvers == null || propertyResolvers.isUnsatisfied()) {
            return context;
        }

        // Build resolution context
        com.e2eq.framework.model.securityrules.ResolutionContext resolutionContext =
            new ResolutionContextImpl(
                securityIdentity,
                credentials,
                context.getDefaultRealm(),
                context.getUserId(),
                currentJwt(),
                requestContext,
                context.getDataDomain(),
                context.getDomainContext()
            );

        // Collect properties from all resolvers (sorted by priority), after any the context already
        // carries (a remote principal's actingService); locally built contexts carry none.
        java.util.Map<String, Object> allProperties = new java.util.LinkedHashMap<>(context.getCustomProperties());

        java.util.List<com.e2eq.framework.model.securityrules.PrincipalContextPropertiesResolver> sortedResolvers =
            new java.util.ArrayList<>();
        propertyResolvers.forEach(sortedResolvers::add);
        sortedResolvers.sort(java.util.Comparator.comparingInt(
            com.e2eq.framework.model.securityrules.PrincipalContextPropertiesResolver::priority));

        for (com.e2eq.framework.model.securityrules.PrincipalContextPropertiesResolver resolver : sortedResolvers) {
            try {
                java.util.Map<String, Object> props = resolver.resolve(resolutionContext);
                if (props != null && !props.isEmpty()) {
                    allProperties.putAll(props);
                    if (Log.isDebugEnabled()) {
                        Log.debugf("PrincipalContextPropertiesResolver %s contributed properties: %s",
                            resolver.getClass().getSimpleName(), props.keySet());
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Principal context resolver failed: " + resolver.getClass().getName(), e);
            }
        }

        if (allProperties.isEmpty()) {
            return context;
        }

        // Rebuild context with custom properties
        PrincipalContext.Builder rebuilt = new PrincipalContext.Builder();
        if (remotePrincipalSource()) {
            // A remote principal names its subject; the local paths below never kept it here.
            rebuilt.withSubjectId(context.getSubjectId());
        }
        return rebuilt
                .withDefaultRealm(context.getDefaultRealm())
                .withApplicationId(context.getApplicationId())
                .withDataDomain(context.getDataDomain())
                .withDomainContext(context.getDomainContext())
                .withUserId(context.getUserId())
                .withRoles(context.getRoles())
                .withScope(context.getScope())
                .withArea2RealmOverrides(context.getArea2RealmOverrides())
                .withDataDomainPolicy(context.getDataDomainPolicy())
                .withImpersonatedBySubject(context.getImpersonatedBySubject())
                .withImpersonatedByUserId(context.getImpersonatedByUserId())
                .withActingOnBehalfOfSubject(context.getActingOnBehalfOfSubject())
                .withActingOnBehalfOfUserId(context.getActingOnBehalfOfUserId())
                .withRealmOverrideActive(context.isRealmOverrideActive())
                .withOriginalDataDomain(context.getOriginalDataDomain())
                .withCustomProperties(allProperties)
                .build();
    }

    private void validateContextInputs(ContainerRequestContext requestContext) {
        String impersonateSubject = requestContext.getHeaderString("X-Impersonate-Subject");
        String impersonateUserId = requestContext.getHeaderString("X-Impersonate-UserId");

        if (impersonateSubject != null && impersonateUserId != null) {
            throw new IllegalArgumentException(String.format(
                "Impersonation Subject: %s and impersonate UserId:%s can only pass one of them but not both",
                impersonateSubject, impersonateUserId));
        }

        String delegatedSubject = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_SUBJECT);
        String delegatedUserId = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_USER_ID);
        String originalSubject = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.ORIGINAL_SUBJECT);
        String originalUserId = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.ORIGINAL_USER_ID);
        String tokenType = claimString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM);
        validateDelegationHeaders(
                tokenType,
                delegatedSubject,
                delegatedUserId,
                originalSubject,
                originalUserId);
    }

    static void validateDelegationHeaders(
            String tokenType,
            String delegatedSubject,
            String delegatedUserId,
            String originalSubject,
            String originalUserId) {
        boolean delegationRequested = nonBlank(delegatedSubject) || nonBlank(delegatedUserId)
                || nonBlank(originalSubject) || nonBlank(originalUserId);
        if (!delegationRequested) {
            return;
        }

        if (!com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                .equalsIgnoreCase(tokenType)) {
            throw new jakarta.ws.rs.ForbiddenException(
                    "Working-on-behalf-of identity is accepted only from a trusted service token");
        }
        if (!nonBlank(delegatedSubject) && !nonBlank(delegatedUserId)) {
            throw new jakarta.ws.rs.BadRequestException(
                    "Working-on-behalf-of requires an effective subject or userId");
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    @jakarta.inject.Inject
    IdentityRoleResolver identityRoleResolver;

    /**
     * Resolve effective roles for a user within a specific realm.
     * @param identity the security identity from the token
     * @param credential the user's credential
     * @param realm the target realm for UserProfile/UserGroup lookups (e.g., from X-Realm header)
     */
    private String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential, String realm) {
        String authorityRealm = credential != null && credential.getDomainContext() != null
                ? credential.getDomainContext().getDefaultRealm()
                : realm;
        return resolveEffectiveRoles(identity, credential, authorityRealm, realm);
    }

    private String[] resolveEffectiveRoles(SecurityIdentity identity,
                                           CredentialUserIdPassword credential,
                                           String authorityRealm,
                                           String effectiveRealm) {
        // In an application session, a token minted for another application carries that
        // application's roles; the session's roles then come only from the credential, the
        // realm assignment and the session application's groups.
        SecurityIdentity roleIdentity = credential != null && foreignApplicationToken(tokenApplicationId())
                ? null
                : identity;
        return resolver().resolveEffectiveRoles(
                roleIdentity, credential, authorityRealm, effectiveRealm, currentApplicationId());
    }

    private void validateRealmAccess(CredentialUserIdPassword creds, String realm) {
        resolver().validateRealmAccess(creds, realm);
    }

    private PrincipalContext handleImpersonation(ContainerRequestContext requestContext, PrincipalContext baseContext) {
        String impersonateSubject = requestContext.getHeaderString("X-Impersonate-Subject");
        String impersonateUserId = requestContext.getHeaderString("X-Impersonate-UserId");
        String actingOnBehalfOfSubject = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_SUBJECT);
        String actingOnBehalfOfUserId = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.EFFECTIVE_USER_ID);
        String originallyAuthenticatedSubject = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.ORIGINAL_SUBJECT);
        String originallyAuthenticatedUserId = requestContext.getHeaderString(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.ORIGINAL_USER_ID);

        if (impersonateSubject == null && impersonateUserId == null) {
            return new PrincipalContext.Builder()
                    .withDefaultRealm(baseContext.getDefaultRealm())
                    .withApplicationId(baseContext.getApplicationId())
                    .withDomainContext(baseContext.getDomainContext())
                    .withDataDomain(baseContext.getDataDomain())
                    .withUserId(baseContext.getUserId())
                    .withRoles(baseContext.getRoles())
                    .withScope(baseContext.getScope())
                    .withArea2RealmOverrides(baseContext.getArea2RealmOverrides())
                    .withDataDomainPolicy(baseContext.getDataDomainPolicy())
                    .withRealmOverrideActive(baseContext.isRealmOverrideActive())
                    .withOriginalDataDomain(baseContext.getOriginalDataDomain())
                    .withActingOnBehalfOfSubject(actingOnBehalfOfSubject)
                    .withActingOnBehalfOfUserId(actingOnBehalfOfUserId)
                    .withImpersonatedBySubject(originallyAuthenticatedSubject)
                    .withImpersonatedByUserId(originallyAuthenticatedUserId)
                    .build();
        }

        // Find the original credential for impersonation validation
        String sub = claimString("sub");
        if (sub == null) {
            throw new IllegalStateException("Cannot impersonate without valid JWT subject");
        }

        Optional<CredentialUserIdPassword> originalCreds = credentialRepo.findBySubject(sub, envConfigUtils.getSystemRealm(), true);
        if (!originalCreds.isPresent()) {
            throw new IllegalStateException("Original user credential not found for impersonation");
        }

        String impersonationRealm = requestContext.getHeaderString("X-Realm");
        if (trustTokenClaims) {
            // The base context skipped the realm switch (baseRealmOverride); check the
            // impersonator's own realm access (authorizedRealms, then realmRegEx) here.
            validateRealmAccess(originalCreds.get(), impersonationRealm);
        }
        validateImpersonationPermissions(originalCreds.get(), impersonationRealm);

        Optional<CredentialUserIdPassword> targetCreds = findImpersonationTarget(impersonateSubject, impersonateUserId);
        if (!targetCreds.isPresent()) {
            throw new WebApplicationException(String.format(
                "Could not find impersonated userId or subject, id:%s",
                (impersonateSubject != null) ? impersonateSubject : impersonateUserId),
                Response.Status.NOT_FOUND);
        }

        return buildImpersonatedContext(targetCreds.get(), originalCreds.get(), actingOnBehalfOfSubject, actingOnBehalfOfUserId);
    }

    private void validateImpersonationPermissions(CredentialUserIdPassword originalCreds, String realm) {
        resolver().validateImpersonationPermissions(originalCreds, realm);
    }

    private Optional<CredentialUserIdPassword> findImpersonationTarget(String impersonateSubject, String impersonateUserId) {
        return resolver().findImpersonationTarget(impersonateSubject, impersonateUserId);
    }

    /**
     * Roles for a delegated (trust-token-claims) principal after an X-Realm switch.
     *
     * <p>A token's role claims mix the issuing realm's {@code UserRealmRole} roles, which apply
     * only in that realm, with flat credential roles, which apply in every realm. The token
     * itself cannot say which is which, and subtracting the issuing realm's local assignment is
     * not enough: an external IdP may assert realm-specific groups the local assignment never
     * lists, and that assignment may be absent or stale. So a token role is carried into the
     * target realm only when it is authoritatively global, i.e. it is also one of the flat roles
     * on the credential found by the token's stable subject (not the userId string). When no
     * credential can be consulted (remote control-plane mode, where credential lookups are
     * control-plane-internal, or no local credential for the subject, or one bound to a different
     * userId) no token role is carried: fail closed. The target realm's own active assignment
     * then supplies the realm roles; with nothing left the principal is {@code ANONYMOUS}.
     */
    String[] delegatedRolesAfterRealmSwitch(String subject, String userId,
                                            java.util.Collection<String> tokenRoles,
                                            String targetRealm) {
        return delegatedRolesAfterRealmSwitch(claimString("iss"), subject, userId, tokenRoles, targetRealm);
    }

    private String[] delegatedRolesAfterRealmSwitch(String issuer, String subject, String userId,
                                                   java.util.Collection<String> tokenRoles, String targetRealm) {
        return RequestRoleResolution.delegated(routingContext, issuer, subject, userId, tokenRoles,
                targetRealm, currentApplicationId(),
                () -> DelegatedSessionRoles.resolve(systemDirectory, realmMembershipService,
                        subject, userId, tokenRoles, targetRealm));
    }

    private PrincipalContext buildImpersonatedContext(CredentialUserIdPassword targetCreds, CredentialUserIdPassword originalCreds,
                                                     String actingOnBehalfOfSubject, String actingOnBehalfOfUserId) {
        // The token is the impersonator's: its role claims are never the target's roles.
        return resolver().buildImpersonatedContext(targetCreds, originalCreds,
                actingOnBehalfOfSubject, actingOnBehalfOfUserId, currentApplicationId());
    }

    /**
     * Applies realm override when X-Realm header is set. When a user switches realms via X-Realm,
     * their DataDomain should be updated to the target realm's default DataDomain so that:
     * 1. Created records are stamped with the correct DataDomain for that realm
     * 2. Query filters use the correct DataDomain for that realm
     *
     * The user's identity (userId, roles) remains unchanged - they stay "themselves" but act
     * within the target realm's data context.
     *
     * @param context the current PrincipalContext
     * @param realm the target realm from X-Realm header (may be null)
     * @return updated PrincipalContext with realm's DataDomain, or original context if no override needed
     */
    PrincipalContext applyRealmOverride(PrincipalContext context, String realm) {
        return resolver().applyRealmOverride(context, realm, trustTokenClaims);
    }

    /**
     * Applies tenant selection without a token tenant claim. See
     * {@link #applyTenantSelection(PrincipalContext, String, String)}.
     */
    PrincipalContext applyTenantSelection(PrincipalContext context, String requestedTenantId) {
        return applyTenantSelection(context, requestedTenantId, null);
    }

    /**
     * Applies the tenant selected inside the effective realm. A legacy or
     * explicitly single-tenant realm derives its tenant from the realm's
     * DomainContext and therefore does not require X-Tenant-Id.
     *
     * <p>In an explicitly multi-tenant realm the tenant resolves from who is
     * signed in: without X-Tenant-Id it is the tenant carried by the verified
     * token ({@code tokenTenantId}). X-Tenant-Id is only an explicit tenant
     * switch. Either way the tenant must be authorized by the user's realm
     * assignment and be an active member of the realm, and the data domain is
     * built from that membership. With neither a header nor a token tenant
     * the request fails closed.
     */
    PrincipalContext applyTenantSelection(PrincipalContext context, String requestedTenantId,
                                          String tokenTenantId) {
        return resolver().applyTenantSelection(context, requestedTenantId, tokenTenantId,
                isPlatformTenantExemptRequest());
    }

    private boolean isServiceTokenMintRequest() {
        return resourceInfo != null && resourceInfo.getResourceClass() != null
                && combinedResourcePath(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod())
                    .toLowerCase(Locale.ROOT).contains("service-token");
    }

    boolean isPlatformTenantExemptRequest() {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return false;
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        if (hasControlPlaneDirectoryMapping(resourceClass) || hasIdentityDirectoryMapping(resourceClass)) {
            return true;
        }
        String combined = combinedResourcePath(resourceClass, resourceInfo.getResourceMethod());
        String normalized = combined.toLowerCase(Locale.ROOT).replace("//", "/");
        return normalized.contains("service-token")
                || normalized.contains("/control/realms")
                || normalized.contains("/v1/auth/identity");
    }

    static boolean hasControlPlaneDirectoryMapping(Class<?> type) {
        return hasFunctionalMapping(type, "system", "control_plane_directory");
    }

    static boolean hasIdentityDirectoryMapping(Class<?> type) {
        return hasFunctionalMapping(type, "security", "identity");
    }

    private static boolean hasFunctionalMapping(Class<?> type, String area, String domain) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (matchesFunctionalMapping(current.getAnnotation(FunctionalMapping.class), area, domain)) {
                return true;
            }
            for (Class<?> iface : current.getInterfaces()) {
                if (matchesFunctionalMapping(iface.getAnnotation(FunctionalMapping.class), area, domain)) {
                    return true;
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean matchesFunctionalMapping(FunctionalMapping mapping, String area, String domain) {
        return mapping != null
                && area.equalsIgnoreCase(mapping.area())
                && domain.equalsIgnoreCase(mapping.domain());
    }

    private static String combinedResourcePath(Class<?> resourceClass, java.lang.reflect.Method resourceMethod) {
        Path classPath = resourceClass.getAnnotation(Path.class);
        Path methodPath = resourceMethod == null ? null : resourceMethod.getAnnotation(Path.class);
        return (classPath == null ? "" : classPath.value())
                + (methodPath == null ? "" : methodPath.value());
    }

    /**
     * Enforces pre-authorization by checking security rules before endpoint execution.
     * Prevents unauthorized access when endpoints bypass Morphia repo filtering.
     *
     * <p>When the endpoint is annotated with {@code @FunctionalAction(bypassDataScoping=true)},
     * SCOPED constraints (data-level filters like {@code dataDomain.tenantId:${pTenantId}}) are
     * ignored. This is intended for system-level operations that don't operate on tenant-scoped
     * data entities.</p>
     */
    private void enforcePreAuthorization(PrincipalContext principalContext, ResourceContext resourceContext,
                                         ContainerRequestContext requestContext) {
        try {
            // Log principal and resource context for debugging
            if (Log.isDebugEnabled()) {
                Log.debugf("enforcePreAuthorization: user=%s, roles=%s, realm=%s, dataDomain=%s",
                    principalContext.getUserId(),
                    principalContext.getRoles() != null ? java.util.Arrays.toString(principalContext.getRoles()) : "[]",
                    principalContext.getDefaultRealm(),
                    principalContext.getDataDomain() != null ? principalContext.getDataDomain().toString() : "null");
                Log.debugf("enforcePreAuthorization: area=%s, domain=%s, action=%s",
                    resourceContext.getArea(),
                    resourceContext.getFunctionalDomain(),
                    resourceContext.getAction());
            }

            com.e2eq.framework.model.securityrules.SecurityCheckResponse response =
                ruleContext.checkRules(principalContext, resourceContext, com.e2eq.framework.model.securityrules.RuleEffect.DENY);

            boolean bypassDataScoping = isBypassDataScoping();

            // Check if access is denied
            if (response.getFinalEffect() != com.e2eq.framework.model.securityrules.RuleEffect.ALLOW) {
                String message = String.format(
                    "Access denied: user=%s, area=%s, domain=%s, action=%s",
                    principalContext.getUserId(),
                    resourceContext.getArea(),
                    resourceContext.getFunctionalDomain(),
                    resourceContext.getAction()
                );

                // Log detailed info at WARN level to help diagnose rule matching issues
                Log.warnf("Pre-authorization DENIED: user=%s, roles=%s, realm=%s, area=%s, domain=%s, action=%s, " +
                          "decision=%s, scope=%s, winningRule=%s, bypassDataScoping=%s",
                    principalContext.getUserId(),
                    principalContext.getRoles() != null ? java.util.Arrays.toString(principalContext.getRoles()) : "[]",
                    principalContext.getDefaultRealm(),
                    resourceContext.getArea(),
                    resourceContext.getFunctionalDomain(),
                    resourceContext.getAction(),
                    response.getDecision(),
                    response.getDecisionScope(),
                    response.getWinningRuleName(),
                    bypassDataScoping);

                if (Log.isDebugEnabled()) {
                    Log.debugf("Pre-authorization failed: %s, decision=%s, scope=%s",
                        message, response.getDecision(), response.getDecisionScope());
                }

                requestContext.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                        .entity(apiError("ACCESS_DENIED", message))
                        .build()
                );
                return;
            }

            // Access is ALLOWED - handle SCOPED decisions
            if ("SCOPED".equals(response.getDecisionScope()) && response.isScopedConstraintsPresent()) {
                if (bypassDataScoping) {
                    // System-level operation - bypass data scoping constraints
                    if (Log.isInfoEnabled()) {
                        Log.infof("Bypassing data scoping for system operation: user=%s, area=%s, domain=%s, action=%s, " +
                                  "scopedConstraints=%s (bypassDataScoping=true)",
                            principalContext.getUserId(),
                            resourceContext.getArea(),
                            resourceContext.getFunctionalDomain(),
                            resourceContext.getAction(),
                            response.getScopedConstraints());
                    }
                    // Allow the request to proceed - data scoping is not applicable for this operation
                } else {
                    // Standard data operation with SCOPED decision
                    // For LIST/VIEW operations, the scoped constraints will be applied at the repo level
                    // For non-list operations without bypassDataScoping, we log a warning but allow
                    // (the repo layer should enforce constraints)
                    if (Log.isDebugEnabled()) {
                        Log.debugf("SCOPED access granted with constraints: user=%s, area=%s, domain=%s, action=%s, " +
                                   "constraints=%s",
                            principalContext.getUserId(),
                            resourceContext.getArea(),
                            resourceContext.getFunctionalDomain(),
                            resourceContext.getAction(),
                            response.getScopedConstraints());
                    }
                }
            }
        } catch (Exception e) {
            com.e2eq.framework.security.principal.RemotePrincipalResolutionException remote = remoteRefusal(e);
            if (e instanceof com.e2eq.framework.model.security.ApplicationAccessException refusal) {
                Log.warnf("Pre-authorization refused for user=%s, path=%s: %s",
                    principalContext.getUserId(), requestContext.getUriInfo().getPath(), refusal.code());
            } else if (remote != null) {
                Log.warnf("Pre-authorization refused for user=%s, path=%s: %d %s (%s)",
                    principalContext.getUserId(), requestContext.getUriInfo().getPath(),
                    remote.status(), remote.code(), remote.getMessage());
            } else {
                Log.errorf(e, "Pre-authorization check failed for user=%s, path=%s",
                    principalContext.getUserId(), requestContext.getUriInfo().getPath());
            }
            requestContext.abortWith(preAuthorizationFailure(e));
        }
    }

    /**
     * A typed application-scope refusal (no application, or principal and resource disagree)
     * keeps its declared status and code. A delegated decision that Auth refused or could not
     * give ({@link com.e2eq.framework.security.principal.RemotePrincipalResolutionException},
     * raised by the authorization provider: 401/403 with Auth's code, or 503
     * {@code PRINCIPAL_RESOLUTION_UNAVAILABLE} when Auth is unreachable) keeps its status and
     * {@code {"code","message"}} body. Any other failure stays a generic 500 whose detail lives
     * only in the server log.
     */
    static Response preAuthorizationFailure(Exception e) {
        if (e instanceof com.e2eq.framework.model.security.ApplicationAccessException refusal) {
            return Response.status(refusal.code().httpStatus())
                .entity(apiError(refusal.code().name(), refusal.publicMessage()))
                .build();
        }
        com.e2eq.framework.security.principal.RemotePrincipalResolutionException remote = remoteRefusal(e);
        if (remote != null) {
            return Response.status(remote.status())
                .header("Cache-Control", "no-store")
                .entity(apiError(remote.code(), remote.publicMessage()))
                .build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(apiError(
                "AUTHORIZATION_CHECK_FAILED",
                "Authorization check failed. See server diagnostics for details."
            ))
            .build();
    }

    /** The typed remote refusal in {@code e} or its causes (an evaluator may wrap it), else null. */
    static com.e2eq.framework.security.principal.RemotePrincipalResolutionException remoteRefusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.e2eq.framework.security.principal.RemotePrincipalResolutionException typed) {
                return typed;
            }
        }
        return null;
    }

    /**
     * Stable error contract consumed by generated SDKs. Authorization diagnostics stay in server
     * logs; response bodies deliberately contain only the public, versioned API error fields.
     */
    static Map<String, String> apiError(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    /**
     * Checks if the current endpoint is annotated with @PermitAll.
     * @Authenticated endpoints bypass all policy checks and are handled entirely by Jakarta Security.
     *
     * @return true if the method or class is annotated with @PermitAll, false otherwise
     */
    private boolean isAuthenticatedEndpoint() {
        if (resourceInfo == null) {
            return false;
        }

        try {
            // Jandex-first lookup with reflection fallback
            if (hasAnnotationJandexFirst(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod(),
                    io.quarkus.security.Authenticated.class.getName())) {
                return true;
            }
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Error checking @Authenticated via Jandex: %s", e.getMessage());
            }
        }

        return false;
    }

    /**
     * Checks if the current endpoint is annotated with @PermitAll.
     * @PermitAll endpoints bypass all policy checks and are handled entirely by Jakarta Security.
     *
     * @return true if the method or class is annotated with @PermitAll, false otherwise
     */
    private boolean isPermitAllEndpoint() {
        if (resourceInfo == null) {
            return false;
        }

        try {
            // Jandex-first lookup with reflection fallback
            if (hasAnnotationJandexFirst(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod(),
                    jakarta.annotation.security.PermitAll.class.getName())) {
                return true;
            }
        } catch (Exception e) {
            if (Log.isDebugEnabled()) {
                Log.debugf("Error checking @PermitAll via Jandex: %s", e.getMessage());
            }
        }

        return false;
    }

    // --- Jandex-first annotation detection with reflection fallback and small caches ---
    private static final java.util.concurrent.ConcurrentMap<String, Boolean> ANNOTATION_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile org.jboss.jandex.IndexView JANDEX_INDEX;

    private static org.jboss.jandex.IndexView getJandexIndexIfAvailable() {
        if (JANDEX_INDEX != null) return JANDEX_INDEX;
        synchronized (SecurityFilter.class) {
            if (JANDEX_INDEX != null) return JANDEX_INDEX;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                java.io.InputStream is = (cl != null)
                        ? cl.getResourceAsStream("META-INF/jandex.idx")
                        : SecurityFilter.class.getClassLoader().getResourceAsStream("META-INF/jandex.idx");
                if (is != null) {
                    try (java.io.InputStream in = is) {
                        JANDEX_INDEX = new org.jboss.jandex.IndexReader(in).read();
                    }
                }
            } catch (Throwable t) {
                // ignore; fallback to reflection
            }
            return JANDEX_INDEX;
        }
    }

    private boolean hasAnnotationJandexFirst(Class<?> resourceClass, java.lang.reflect.Method method, String annotationFqn) {
        if (resourceClass == null) return false;
        String cacheKey = buildAnnotationCacheKey(resourceClass, method, annotationFqn);
        Boolean cached = ANNOTATION_CACHE.get(cacheKey);
        if (cached != null) return cached;

        boolean present = false;

        org.jboss.jandex.IndexView index = getJandexIndexIfAvailable();
        if (index != null) {
            try {
                org.jboss.jandex.DotName ann = org.jboss.jandex.DotName.createSimple(annotationFqn);
                org.jboss.jandex.ClassInfo ci = index.getClassByName(org.jboss.jandex.DotName.createSimple(resourceClass.getName()));
                if (ci != null) {
                    // method-level first
                    if (method != null) {
                        java.util.List<org.jboss.jandex.MethodInfo> methods = ci.methods();
                        for (org.jboss.jandex.MethodInfo mi : methods) {
                            if (mi.name().equals(method.getName()) && mi.parameterTypes().size() == method.getParameterCount()) {
                                if (mi.hasAnnotation(ann)) { present = true; break; }
                            }
                        }
                    }
                    // class-level if not found
                    if (!present) {
                        if (ci.hasAnnotation(ann)) {
                            present = true;
                        }
                    }
                }
            } catch (Throwable t) {
                // ignore and fall back to reflection
            }
        }

        if (!present) {
            // Fallback: reflection
            if (method != null) {
                try {
                    if (method.getAnnotation((Class)Class.forName(annotationFqn)) != null) {
                        present = true;
                    }
                } catch (ClassNotFoundException ignored) { }
            }
            if (!present) {
                try {
                    if (resourceClass.getAnnotation((Class)Class.forName(annotationFqn)) != null) {
                        present = true;
                    }
                } catch (ClassNotFoundException ignored) { }
            }
        }

        ANNOTATION_CACHE.putIfAbsent(cacheKey, present);
        return present;
    }

    private static String buildAnnotationCacheKey(Class<?> clazz, java.lang.reflect.Method method, String annFqn) {
        String m = (method == null) ? "<class>" : method.getName() + "#" + method.getParameterCount();
        return clazz.getName() + "|" + m + "|" + annFqn;
    }
    public boolean matchesRealmFilter(String realm, String filterPattern) {
        if (filterPattern == null || realm == null)
            return false;

        if (realm.trim().isBlank() ) {
            Log.warnf("Realm is blank, this is not allowed, realm:%s", realm);
            return false;
        }

        if (filterPattern.isBlank()) {
            Log.warnf("Filter pattern is blank, this is not allowed, filter pattern:%s", filterPattern);
            return false;
        }
        // Convert wildcard pattern to regex
        String regex = filterPattern.replace("*", ".*");
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(realm);
        return matcher.matches();
    }
}
