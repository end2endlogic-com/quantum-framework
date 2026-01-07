package com.e2eq.framework.rest.filters;


import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Provider
//@PreMatching
//@PermissionCheck
public class SecurityFilter implements ContainerRequestFilter, jakarta.ws.rs.container.ContainerResponseFilter {

    @jakarta.ws.rs.core.Context
    jakarta.ws.rs.container.ResourceInfo resourceInfo;

    private static final String AUTHENTICATION_SCHEME = "Bearer";

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    RealmRepo realmRepo;

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

    @ConfigProperty(name = "quantum.security.scripting.maxMemoryBytes", defaultValue = "10000000")
    long scriptingMaxMemoryBytes;

    @ConfigProperty(name = "quantum.security.scripting.maxStatements", defaultValue = "10000")
    long scriptingMaxStatements;

    @ConfigProperty(name = "quantum.security.filter.enforcePreAuth", defaultValue = "true")
    boolean enforcePreAuth;

    @Inject
    com.e2eq.framework.securityrules.RuleContext ruleContext;

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_PERMISSIVE = new java.util.concurrent.atomic.AtomicBoolean(false);


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
                com.e2eq.framework.securityrules.RuleContext.initRequestCacheIfAbsent();
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

        if (requestContext.getUriInfo().getPath().contains("/system/migration") && securityIdentity != null && securityIdentity.getRoles().contains("admin")) {
           Log.warnf("System migration detected, setting up system principal context");
           resourceContext = determineResourceContext(requestContext);
           principalContext = securityUtils.getSystemPrincipalContext();
        } else {
            resourceContext = determineResourceContext(requestContext);
            principalContext = determinePrincipalContext(requestContext);
        }

        if (principalContext == null) {
            throw new IllegalStateException("Principal context came back null and should not be null");
        }

        // Always set the contexts - they may be needed by other parts of the application
        // even for @PermitAll endpoints. The policy checks will be bypassed elsewhere.
        SecurityContext.setPrincipalContext(principalContext);
        SecurityContext.setResourceContext(resourceContext);

        // Pre-authorization check: enforce policy rules before endpoint execution
        boolean skipPreAuth = isPermitAllEndPoint || isAuthenticatedEndPoint;
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
                com.e2eq.framework.securityrules.RuleContext.clearRequestCache();
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
                if (com.e2eq.framework.rest.resources.BaseResource.class.isAssignableFrom((Class<?>) pt.getRawType())) {
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

    private boolean runScript(String subject, String userId,  String realm, String script) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(userId, "UserId cannot be null");
        Objects.requireNonNull(realm, "Realm cannot be null");
        Objects.requireNonNull(script, "Script cannot be null");

        Log.debugf("Running impersonation filter script for user:%s, userId:%s, realm:%s", subject, userId, realm);

        // Resolve scripting config with runtime fallback (when not CDI-injected)
        boolean enabled = scriptingEnabled;
        boolean allowAll = scriptingAllowAllAccess;
        long timeoutMs = scriptingTimeoutMillis;
        long maxMemoryBytes = scriptingMaxMemoryBytes;
        long maxStatementsValue = scriptingMaxStatements;
        try {
            org.eclipse.microprofile.config.Config cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig();
            if (cfg != null) {
                enabled = cfg.getOptionalValue("quantum.security.scripting.enabled", Boolean.class).orElse(Boolean.TRUE);
                allowAll = cfg.getOptionalValue("quantum.security.scripting.allowAllAccess", Boolean.class).orElse(Boolean.FALSE);
                timeoutMs = cfg.getOptionalValue("quantum.security.scripting.timeout.millis", Long.class).orElse(1500L);
                maxMemoryBytes = cfg.getOptionalValue("quantum.security.scripting.maxMemoryBytes", Long.class).orElse(10000000L);
                maxStatementsValue = cfg.getOptionalValue("quantum.security.scripting.maxStatements", Long.class).orElse(10000L);
            }
        } catch (Throwable ignored) {
            if (timeoutMs <= 0) timeoutMs = 1500L;
            if (maxMemoryBytes <= 0) maxMemoryBytes = 10000000L;
            if (maxStatementsValue <= 0) maxStatementsValue = 10000L;
        }
        if (timeoutMs < 500L) timeoutMs = 1500L;
        // Make final for use in lambda
        final long maxStatements = maxStatementsValue;

        if (!enabled) {
            Log.warn("Security scripting is disabled via config; returning false");
            return false;
        }

        // Permissive mode: Only allow in development/test environments via system property
        if (allowAll) {
            String permissiveEnv = System.getProperty("quantum.security.scripting.allowPermissiveEnv", "false");
            if (!"true".equals(permissiveEnv) && !"dev".equals(permissiveEnv) && !"test".equals(permissiveEnv)) {
                Log.error("Permissive script mode requested but not allowed in this environment. Set system property quantum.security.scripting.allowPermissiveEnv=true to enable (UNSAFE).");
                throw new SecurityException("Permissive script execution not allowed in this environment");
            }
            if (WARNED_PERMISSIVE.compareAndSet(false, true)) {
                Log.warn("Permissive script mode enabled - UNSAFE - only for development/testing. This should never be used in production.");
            }
            try (Context c = Context.newBuilder("js").allowAllAccess(true).build()) {
                c.getBindings("js").putMember("subject", subject);
                c.getBindings("js").putMember("userId", userId);
                c.getBindings("js").putMember("realm", realm);
                Value v = c.eval("js", script);
                return v.isBoolean() ? v.asBoolean() : false;
            } catch (Throwable t) {
                Log.warn("Script execution failed in permissive mode; returning false", t);
                return false;
            }
        }

        // Hardened mode with timeout and resource limits
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "impersonation-script-worker");
            th.setDaemon(true);
            return th;
        });
        try {
            // Monitor memory usage
            Runtime runtime = Runtime.getRuntime();
            long beforeMemory = runtime.totalMemory() - runtime.freeMemory();

            java.util.concurrent.Future<Boolean> fut = executor.submit(() -> {
                Engine eng = Engine.newBuilder().build();
                try (Context c = Context.newBuilder("js")
                        .engine(eng)
                        .allowAllAccess(false)
                        // Enhanced security: disable public access, only allow array/list access for bindings
                        .allowHostAccess(HostAccess.newBuilder()
                                .allowPublicAccess(false)  // Disable public access
                                .allowArrayAccess(true)    // Only allow array access for bindings
                                .allowListAccess(true)     // Only allow list access for bindings
                                .build())
                        .allowHostClassLookup(s -> false)  // Disable class lookup
                        .allowIO(false)                     // Disable I/O
                        .allowNativeAccess(false)           // Disable native access
                        .allowCreateThread(false)           // Disable thread creation
                        .allowCreateProcess(false)          // Disable process creation
                        .option("js.ecmascript-version", "2021")
                        .build()) {
                    c.getBindings("js").putMember("subject", subject);
                    c.getBindings("js").putMember("userId", userId);
                    c.getBindings("js").putMember("realm", realm);
                    Value v = c.eval("js", script);
                    return v.isBoolean() ? v.asBoolean() : false;
                }
            });
            Boolean result = fut.get(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);

            // Check memory usage
            long afterMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryUsed = afterMemory - beforeMemory;
            if (memoryUsed > maxMemoryBytes) {
                Log.warnf("Script exceeded memory limit: %d bytes (limit: %d bytes); returning false", memoryUsed, maxMemoryBytes);
                return false;
            }

            return result;
        } catch (java.util.concurrent.TimeoutException te) {
            Log.warnf("Impersonation script timed out after %d ms; returning false", (timeoutMs <= 0 ? 1500L : timeoutMs));
            return false;
        } catch (Throwable t) {
            Log.warn("Impersonation script execution failed; returning false", t);
            return false;
        } finally {
            executor.shutdownNow();
        }

    }

    protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
        validateContextInputs(requestContext);

        String realm = requestContext.getHeaderString("X-Realm");
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        PrincipalContext baseContext = buildBaseContext(authHeader, realm);
        PrincipalContext contextWithImpersonation = handleImpersonation(requestContext, baseContext);
        return applyRealmOverride(contextWithImpersonation, realm);
    }

    private void validateContextInputs(ContainerRequestContext requestContext) {
        String impersonateSubject = requestContext.getHeaderString("X-Impersonate-Subject");
        String impersonateUserId = requestContext.getHeaderString("X-Impersonate-UserId");

        if (impersonateSubject != null && impersonateUserId != null) {
            throw new IllegalArgumentException(String.format(
                "Impersonation Subject: %s and impersonate UserId:%s can only pass one of them but not both",
                impersonateSubject, impersonateUserId));
        }
    }

    private PrincipalContext buildBaseContext(String authHeader, String realm) {
        if (authHeader != null && jwt != null) {
            return buildJwtContext(authHeader, realm);
        } else if (securityIdentity != null && !securityIdentity.isAnonymous()) {
            return buildIdentityContext(realm);
        } else {
            return buildAnonymousContext(realm);
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

    private PrincipalContext buildIdentityContext(String realm) {
        String principalName = securityIdentity.getPrincipal() != null ?
            securityIdentity.getPrincipal().getName() : envConfigUtils.getAnonymousUserId();
        String[] roles = resolveEffectiveRoles(securityIdentity, null);
        String contextRealm = (realm != null) ? realm : envConfigUtils.getSystemRealm();

        Optional<CredentialUserIdPassword> ocreds = (realm == null)
                ? credentialRepo.findByUserId(principalName, envConfigUtils.getSystemRealm(), true)
                : credentialRepo.findByUserId(principalName, realm, true);

        if (ocreds.isPresent()) {
            CredentialUserIdPassword creds = ocreds.get();
            validateRealmAccess(creds, realm);

            contextRealm = (realm == null) ? creds.getDomainContext().getDefaultRealm() : realm;
            DataDomain dataDomain = creds.getDomainContext().toDataDomain(creds.getUserId());
            roles = resolveEffectiveRoles(securityIdentity, creds);

            return new PrincipalContext.Builder()
                    .withDefaultRealm(contextRealm)
                    .withDataDomain(dataDomain)
                    .withUserId(creds.getUserId())
                    .withRoles(roles)
                    .withScope("AUTHENTICATED")
                    .withDataDomainPolicy(creds.getDataDomainPolicy())
                    .build();
        } else {
            return new PrincipalContext.Builder()
                    .withDefaultRealm(contextRealm)
                    .withDataDomain(securityUtils.getSystemDataDomain())
                    .withUserId(principalName)
                    .withRoles(roles)
                    .withScope("AUTHENTICATED")
                    .build();
        }
    }

    private PrincipalContext buildJwtContext(String authHeader, String realm) {
        // Extract token (variable kept for potential future use)
        @SuppressWarnings("unused")
        String token = authHeader.substring(AUTHENTICATION_SCHEME.length()).trim();
        String sub = jwt.getClaim("sub");

        if (sub == null) {
            throw new IllegalStateException("sub attribute not provided but is required in token claims");
        }

        Optional<CredentialUserIdPassword> ocreds = credentialRepo.findBySubject(sub, envConfigUtils.getSystemRealm(), true);
        if (!ocreds.isPresent()) {
            ocreds = findCredentialByUsername(sub, realm);
        }

        if (!ocreds.isPresent()) {
            throw new IllegalStateException(String.format(
                "Subject %s not found in credentialUserIdPassword collection in realm:%s",
                sub, envConfigUtils.getSystemRealm()));
        }

        CredentialUserIdPassword creds = ocreds.get();
        validateRealmAccess(creds, realm);

        String[] roles = resolveEffectiveRoles(securityIdentity, creds);
        DataDomain dataDomain = creds.getDomainContext().toDataDomain(creds.getUserId());

        String effectiveRealm = (realm != null) ? realm : creds.getDomainContext().getDefaultRealm();
        return new PrincipalContext.Builder()
                .withDefaultRealm(effectiveRealm)
                .withDataDomain(dataDomain)
                .withUserId(creds.getUserId())
                .withRoles(roles)
                .withScope("AUTHENTICATED")
                .withArea2RealmOverrides(creds.getArea2RealmOverrides())
                .withDataDomainPolicy(creds.getDataDomainPolicy())
                .build();
    }

    private Optional<CredentialUserIdPassword> findCredentialByUsername(String sub, String realm) {
        String username = jwt.getClaim("username");
        if (username != null) {
            Optional<CredentialUserIdPassword> ocreds = credentialRepo.findByUserId(username, envConfigUtils.getSystemRealm(), true);
            if (ocreds.isPresent()) {
                String text = String.format(
                    "Found user with userId %s but subject is:%s but token has subject:%s in the database, roles in credential is %s",
                    username, ocreds.get().getSubject(), sub, Arrays.toString(ocreds.get().getRoles()));
                Log.warn(text);
                throw new IllegalStateException(text);
            }
        }
        return Optional.empty();
    }

    @jakarta.inject.Inject
    IdentityRoleResolver identityRoleResolver;

    private String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential) {
        // Delegate to centralized resolver to keep logic consistent across endpoints and filter
        if (identityRoleResolver != null) {
            return identityRoleResolver.resolveEffectiveRoles(identity, credential);
        }
        // Fallback for unit tests that construct SecurityFilter without CDI injection
        java.util.Set<String> rolesSet = new java.util.LinkedHashSet<>();
        if (identity != null) {
            for (String role : identity.getRoles()) {
                if (role != null && !role.isEmpty()) {
                    rolesSet.add(role);
                }
            }
        }
        if (credential != null && credential.getRoles() != null && credential.getRoles().length > 0) {
            rolesSet.addAll(java.util.Arrays.asList(credential.getRoles()));
        }
        if (credential != null) {
            java.util.Optional<com.e2eq.framework.model.security.UserProfile> userProfile = userProfileRepo.getBySubject(credential.getSubject());
            if (userProfile.isPresent()) {
                java.util.List<com.e2eq.framework.model.security.UserGroup> userGroups = userGroupRepo.findByUserProfileRef(userProfile.get().createEntityReference());
                if (!userGroups.isEmpty()) {
                    userGroups.forEach(userGroup -> rolesSet.addAll(userGroup.getRoles().stream().toList()));
                }
            }
        }
        return rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[0]);
    }

    private void validateRealmAccess(CredentialUserIdPassword creds, String realm) {
        if (realm != null) {
            List<Realm> realmsAvailable = realmRepo.getAllListWithIgnoreRules(envConfigUtils.getSystemRealm());
            List<String> realmRefNamesAvailable = new java.util.ArrayList<>(realmsAvailable.stream().map(Realm::getRefName).toList());

            if (!realmRefNamesAvailable.contains(realm)) {
                if (Log.isDebugEnabled()) {
                    Log.debugf("Available realms determined by realm Collection in database: %s,  values: %s", envConfigUtils.getSystemRealm(), realmRefNamesAvailable.stream().collect(Collectors.joining(", ") ));
                }
                throw new IllegalArgumentException(String.format(
                    "The realm override %s is not a configured Realm RefName in the realm collection in the database:%s", realm, envConfigUtils.getSystemRealm()));
            }

            List<String> realmsAuthorized = securityUtils.computeAllowedRealmRefNames(creds, realmRefNamesAvailable);
            if (!realmsAuthorized.contains(realm)) {
                throw new IllegalArgumentException(String.format(
                    "The user %s is not authorized to access realm %s", creds.getUserId(), realm));
            }
        }
    }

    private PrincipalContext handleImpersonation(ContainerRequestContext requestContext, PrincipalContext baseContext) {
        String impersonateSubject = requestContext.getHeaderString("X-Impersonate-Subject");
        String impersonateUserId = requestContext.getHeaderString("X-Impersonate-UserId");
        String actingOnBehalfOfSubject = requestContext.getHeaderString("X-Acting-On-Behalf-Of-Subject");
        String actingOnBehalfOfUserId = requestContext.getHeaderString("X-Acting-On-Behalf-Of-UserId");

        if (impersonateSubject == null && impersonateUserId == null) {
            return new PrincipalContext.Builder()
                    .withDefaultRealm(baseContext.getDefaultRealm())
                    .withDataDomain(baseContext.getDataDomain())
                    .withUserId(baseContext.getUserId())
                    .withRoles(baseContext.getRoles())
                    .withScope(baseContext.getScope())
                    .withArea2RealmOverrides(baseContext.getArea2RealmOverrides())
                    .withDataDomainPolicy(baseContext.getDataDomainPolicy())
                    .withActingOnBehalfOfSubject(actingOnBehalfOfSubject)
                    .withActingOnBehalfOfUserId(actingOnBehalfOfUserId)
                    .build();
        }

        // Find the original credential for impersonation validation
        String sub = jwt != null ? jwt.getClaim("sub") : null;
        if (sub == null) {
            throw new IllegalStateException("Cannot impersonate without valid JWT subject");
        }

        Optional<CredentialUserIdPassword> originalCreds = credentialRepo.findBySubject(sub, envConfigUtils.getSystemRealm(), true);
        if (!originalCreds.isPresent()) {
            throw new IllegalStateException("Original user credential not found for impersonation");
        }

        validateImpersonationPermissions(originalCreds.get(), requestContext.getHeaderString("X-Realm"));

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
        if (originalCreds.getImpersonateFilterScript() == null) {
            throw new IllegalArgumentException(String.format(
                "subject %s with userId %s is not configured with a impersonateFilter in realm:%s",
                originalCreds.getSubject(), originalCreds.getUserId(), credentialRepo.getDatabaseName()));
        }

        String targetRealm = (realm != null) ? realm : credentialRepo.getDatabaseName();
        if (!runScript(originalCreds.getSubject(), originalCreds.getUserId(), targetRealm, originalCreds.getImpersonateFilterScript())) {
            throw new WebApplicationException(String.format(
                "User %s with userId %s is not authorized to impersonate in realm:%s",
                originalCreds.getSubject(), originalCreds.getUserId(), targetRealm),
                Response.Status.FORBIDDEN);
        }
    }

    private Optional<CredentialUserIdPassword> findImpersonationTarget(String impersonateSubject, String impersonateUserId) {
        if (impersonateSubject != null) {
            return credentialRepo.findBySubject(impersonateSubject, envConfigUtils.getSystemRealm(), true);
        } else if (impersonateUserId != null) {
            return credentialRepo.findByUserId(impersonateUserId, envConfigUtils.getSystemRealm(), true);
        }
        throw new IllegalStateException("Neither impersonateSubject nor impersonateUserId provided");
    }

    private PrincipalContext buildImpersonatedContext(CredentialUserIdPassword targetCreds, CredentialUserIdPassword originalCreds,
                                                     String actingOnBehalfOfSubject, String actingOnBehalfOfUserId) {
        String[] roles = resolveEffectiveRoles(securityIdentity, targetCreds);
        DataDomain dataDomain = targetCreds.getDomainContext().toDataDomain(targetCreds.getUserId());

        return new PrincipalContext.Builder()
                .withDefaultRealm(targetCreds.getDomainContext().getDefaultRealm())
                .withDataDomain(dataDomain)
                .withUserId(targetCreds.getUserId())
                .withRoles(roles)
                .withScope("AUTHENTICATED")
                .withArea2RealmOverrides(targetCreds.getArea2RealmOverrides())
                .withImpersonatedBySubject(originalCreds.getSubject())
                .withImpersonatedByUserId(originalCreds.getUserId())
                .withActingOnBehalfOfUserId(actingOnBehalfOfUserId)
                .withActingOnBehalfOfSubject(actingOnBehalfOfSubject)
                .withDataDomainPolicy(targetCreds.getDataDomainPolicy())
                .build();
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
    private PrincipalContext applyRealmOverride(PrincipalContext context, String realm) {
        // No realm override requested, or already on that realm
        if (realm == null || realm.isBlank() || realm.equals(context.getDefaultRealm())) {
            return context;
        }
        
        // Skip if impersonation is active - impersonation takes precedence
        if (context.getImpersonatedBySubject() != null || context.getImpersonatedByUserId() != null) {
            Log.debugf("Realm override skipped - impersonation is active for user %s", context.getUserId());
            return context;
        }
        
        // Look up the target realm to get its default DomainContext
        // Use system realm for the lookup since realms are typically stored there
        Optional<com.e2eq.framework.model.security.Realm> targetRealmOpt = 
            realmRepo.findByRefName(realm, true, envConfigUtils.getSystemRealm());
        
        // If not found by refName, try by databaseName (they're often the same)
        if (targetRealmOpt.isEmpty()) {
            targetRealmOpt = realmRepo.findByDatabaseName(realm, true, envConfigUtils.getSystemRealm());
        }
        
        if (targetRealmOpt.isEmpty()) {
            // Realm not found - log warning and return original context
            // The realm was already validated in validateRealmAccess(), so this shouldn't normally happen
            Log.warnf("Realm '%s' not found during realm override for user %s - using original DataDomain", 
                realm, context.getUserId());
            return context;
        }
        
        com.e2eq.framework.model.security.Realm targetRealm = targetRealmOpt.get();
        com.e2eq.framework.model.security.DomainContext targetDomainContext = targetRealm.getDomainContext();
        
        if (targetDomainContext == null) {
            // Realm has no DomainContext configured
            Log.warnf("Realm '%s' has no DomainContext configured - cannot apply realm override for user %s", 
                realm, context.getUserId());
            return context;
        }
        
        // Create the effective DataDomain using the target realm's context but keeping caller's userId as owner
        DataDomain effectiveDataDomain = targetDomainContext.toDataDomain(context.getUserId());
        
        Log.infof("Applying realm override: user=%s, originalRealm=%s, targetRealm=%s, " +
                  "originalDataDomain=%s, effectiveDataDomain=%s",
            context.getUserId(), 
            context.getDefaultRealm(), 
            realm,
            context.getDataDomain(),
            effectiveDataDomain);
        
        // Rebuild the context with the target realm's DataDomain
        return new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withDataDomain(effectiveDataDomain)
                .withUserId(context.getUserId())
                .withRoles(context.getRoles())
                .withScope(context.getScope())
                .withArea2RealmOverrides(context.getArea2RealmOverrides())
                .withDataDomainPolicy(context.getDataDomainPolicy())
                .withImpersonatedBySubject(context.getImpersonatedBySubject())
                .withImpersonatedByUserId(context.getImpersonatedByUserId())
                .withActingOnBehalfOfSubject(context.getActingOnBehalfOfSubject())
                .withActingOnBehalfOfUserId(context.getActingOnBehalfOfUserId())
                // Track realm override for audit purposes
                .withRealmOverrideActive(true)
                .withOriginalDataDomain(context.getDataDomain())
                .build();
    }

    /**
     * Enforces pre-authorization by checking security rules before endpoint execution.
     * Prevents unauthorized access when endpoints bypass Morphia repo filtering.
     */
    private void enforcePreAuthorization(PrincipalContext principalContext, ResourceContext resourceContext,
                                         ContainerRequestContext requestContext) {
        try {
            com.e2eq.framework.model.securityrules.SecurityCheckResponse response =
                ruleContext.checkRules(principalContext, resourceContext, com.e2eq.framework.model.securityrules.RuleEffect.DENY);

            if (response.getFinalEffect() != com.e2eq.framework.model.securityrules.RuleEffect.ALLOW) {
                String message = String.format(
                    "Access denied: user=%s, area=%s, domain=%s, action=%s",
                    principalContext.getUserId(),
                    resourceContext.getArea(),
                    resourceContext.getFunctionalDomain(),
                    resourceContext.getAction()
                );

                if (Log.isDebugEnabled()) {
                    Log.debugf("Pre-authorization failed: %s, decision=%s, scope=%s",
                        message, response.getDecision(), response.getDecisionScope());
                }

                requestContext.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of(
                            "error", "Access Denied",
                            "message", message,
                            "decision", response.getDecision() != null ? response.getDecision() : "DENY",
                            "scope", response.getDecisionScope() != null ? response.getDecisionScope() : "DEFAULT"
                        ))
                        .build()
                );
            }
        } catch (Exception e) {
            Log.errorf(e, "Pre-authorization check failed for user=%s, path=%s",
                principalContext.getUserId(), requestContext.getUriInfo().getPath());
            requestContext.abortWith(
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Authorization check failed"))
                    .build()
            );
        }
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



    /*
    protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
        PrincipalContext pcontext = null;

        // Get the Authorization header from the request
        String authorizationHeader =
                requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        // so normally you might think this would work but since its based upon all the other
        // infrastructure you caught in a catch 22 where this code needs the principal context to form the rules
        // which don't exist yet, so in reality we probably need a different mechanism

        if (authorizationHeader != null) {
            String token = authorizationHeader
                    .substring(AUTHENTICATION_SCHEME.length()).trim();

            String principal = null;
            String scope = null;
            String[] roles = new String[0];

            // principal = requestContext.getSecurityContext().getUserPrincipal().getName();
            // the same as  the following but more consistent as its from the token and we can't predict
            // what happen upsstream of this.
            String userId = jwt.getClaim("username");


            if (userId != null) {
                Optional<CredentialUserIdPassword> ocreds = credentialRepo.findByUserId(userId);
                DomainContext domainContext;
                // if we found the credentials  look to see if we are overriding the roles if not take what ever
                // is in the token
                if (ocreds.isPresent()) {

                    CredentialUserIdPassword creds = ocreds.get();
                    // if the credentials contains roles then use those and ignore what ever is in the token
                    if (creds.getRoles() == null || creds.getRoles().length == 0) {
                        Set<String> rolesSet = securityIdentity.getRoles();
                        if (rolesSet.isEmpty()) {
                            Log.warnf("No roles found for user: %s in the token or defined in the credentials set to anonymous", userId);
                            roles[0] = "ANONYMOUS";
                            creds.setRoles(roles);
                        } else {
                            creds.setRoles(rolesSet.toArray(roles));
                        }
                    }

                    // set the domain context and return the principal context
                    domainContext = creds.getDomainContext();
                    DataDomain dataDomain = domainContext.toDataDomain(userId);
                    pcontext = new PrincipalContext.Builder()
                            .withDefaultRealm(domainContext.getDefaultRealm())
                            .withDataDomain(dataDomain)
                            .withUserId(userId)
                            .withRoles(creds.getRoles())
                            .withScope("AUTHENTICATED")
                            .build();
                } else {
                    // look to see if there is a realm defined for the user
                    // given the userid is an email address if so then look for a realm defined for the user

                    // check if userid is an email address
                    if (ValidateUtils.isValidEmailAddress(userId)) {
                        // if so then look for a realm defined for the user
                        String emailDomain = userId.substring(userId.indexOf("@") + 1);
                        Optional<Realm> orealm = realmRepo.findByEmailDomain(emailDomain);
                        if (orealm.isPresent()) {
                            Realm realm = orealm.get();
                            domainContext = realm.getDomainContext();
                            DataDomain dataDomain = domainContext.toDataDomain(userId);

                            Set<String> rolesSet = securityIdentity.getRoles();
                            if (rolesSet.isEmpty()) {
                                Log.warnf("No roles found for user: %s in the token or defined in the credentials set to anonymous", userId);
                                roles[0] = "ANONYMOUS";
                            } else {
                                roles = rolesSet.toArray(roles);
                            }

                            pcontext = new PrincipalContext.Builder()
                                    .withDefaultRealm(domainContext.getDefaultRealm())
                                    .withDataDomain(dataDomain)
                                    .withUserId(userId)
                                    .withRoles(roles)
                                    .withScope("AUTHENTICATED")
                                    .build();
                        }
                    }
                }

                // so either there was no userid claim found or the user could not be found in the credentials repo
                if (pcontext == null) {
                    if (userId == null) {
                        Log.warn("No userid claim in the token for user assuming anonymous check provider and ensure a userId claim is in the token");
                    } else {
                        Log.warn("Could not find a user with the id:" + userId + " in credentials repo so assuming anonymous");
                    }

                    // could not find the user so assume anonymous
                    roles[0] = "ANONYMOUS";
                    pcontext = new PrincipalContext.Builder()
                            .withDefaultRealm(securityUtils.getSystemRealm())
                            .withDataDomain(securityUtils.getSystemDataDomain())
                            .withUserId(SecurityUtils.anonymousUserId)
                            .withRoles(roles)
                            .withScope("systemGenerated")
                            .build();
                }
            } else {
                // No Auth header found
                if (Log.isEnabled(Logger.Level.WARN))
                    Log.warnf("No Authorization header presented for request: %s", requestContext.getUriInfo().getRequestUri());

                // Lets see if there is a tenantId:
                String tenantId = requestContext.getUriInfo().getQueryParameters().getFirst("tenantId");
                if (tenantId != null) {
                    Optional<Realm> orealm = realmRepo.findByTenantId(tenantId);

                    if (orealm.isPresent()) {
                        Realm realm = orealm.get();
                        DataDomain dataDomain = realm.getDomainContext().toDataDomain(realm.getDefaultAdminUserId());

                        Optional<CredentialUserIdPassword> ocreds = credentialRepo.findByUserId(tenantId, realm.getDefaultAdminUserId());
                        if (ocreds.isPresent()) {
                            CredentialUserIdPassword cred = ocreds.get();
                            roles = cred.getRoles();
                            if ( roles == null || roles.length ==0) {
                                Set<String> rolesSet = securityIdentity.getRoles();
                                if (rolesSet.isEmpty()) {
                                    Log.warnf("No roles found for user: %s in the token or defined in the credentials set to anonymous", userId);
                                    roles[0] = "ANONYMOUS";
                                    cred.setRoles(roles);
                                } else {
                                    cred.setRoles(rolesSet.toArray(roles));
                                }
                            }
                            pcontext = new PrincipalContext.Builder()
                                    .withDefaultRealm(realm.getRefName())
                                    .withDataDomain(dataDomain)
                                    .withUserId(realm.getDefaultAdminUserId())
                                    .withRoles(roles)
                                    .withScope("systemGenerated")
                                    .build();

                        } else {
                            throw new IllegalStateException("Default userId:" + realm.getDefaultAdminUserId() + " could not be found for tenantId:" + tenantId);
                        }
                    } else {
                        // realm could not be found so we should punt
                        throw new NotFoundException("Realm for tenantId:" + tenantId + " not found");
                    }
                } else {
                    Log.warnf("Setting roles on Principal context to ANONYMOUS");
                    // so no auth token, no tenantId passed, no way to figure out tenant so default to system, and set an
                    // anonymous user context
                    roles[0] = "ANONYMOUS";
                    pcontext = new PrincipalContext.Builder()
                            .withDefaultRealm(securityUtils.getSystemRealm())
                            .withDataDomain(securityUtils.getSystemDataDomain())
                            .withUserId(SecurityUtils.anonymousUserId)
                            .withRoles(roles)
                            .withScope("systemGenerated")
                            .build();
                }

            }

            return pcontext;
        }


    }
    */
