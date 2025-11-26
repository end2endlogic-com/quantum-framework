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
import io.quarkus.security.identity.SecurityIdentity;
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

        // we ignore the hello endpoint because it should never require authroization
        // and is used for heart beats, so we just let it go through
        if (requestContext.getUriInfo().getPath().contains("hello")) {
            return;
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

        SecurityContext.setPrincipalContext(principalContext);
        SecurityContext.setResourceContext(resourceContext);

    }

        @Override
        public void filter(ContainerRequestContext requestContext, jakarta.ws.rs.container.ContainerResponseContext responseContext) throws IOException {
            // Clear ThreadLocal contexts after response is produced
            SecurityContext.clear();
        }


        protected ResourceContext determineResourceContext(ContainerRequestContext requestContext) {

        if (!SecurityContext.getResourceContext().isPresent()) {
            ResourceContext rcontext = null;

            // First, prefer annotations if available on the matched resource and method
            try {
                if (resourceInfo != null && resourceInfo.getResourceClass() != null) {
                    Class<?> rc = resourceInfo.getResourceClass();
                    java.lang.reflect.Method rm = resourceInfo.getResourceMethod();
                    com.e2eq.framework.annotations.FunctionalMapping fm = rc.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
                    com.e2eq.framework.annotations.FunctionalAction fa = (rm != null) ? rm.getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class) : null;
                    if (fm != null) {
                        String area = fm.area();
                        String functionalDomain = fm.domain();
                        String action = (fa != null) ? fa.value() : inferActionFromHttpMethod(requestContext.getMethod());
                        rcontext = new ResourceContext.Builder()
                                .withArea(area)
                                .withFunctionalDomain(functionalDomain)
                                .withAction(action)
                                .build();
                        SecurityContext.setResourceContext(rcontext);
                        if (Log.isDebugEnabled()) {
                            Log.debugf("Resource Context set from annotations: area=%s, domain=%s, action=%s", area, functionalDomain, action);
                        }
                        return rcontext;
                    }
                }
            } catch (Exception ex) {
                Log.debugf("Annotation-based resource context resolution failed: %s", ex.toString());
            }

            /**
             * Enhanced path-based resource context resolution
             * Handles variable path segment counts (1-4+ segments)
             */
            String path = requestContext.getUriInfo().getPath();
            if (path == null || path.isEmpty()) {
                rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
                SecurityContext.setResourceContext(rcontext);
                return rcontext;
            }
            
            // Remove leading/trailing slashes and split
            path = path.startsWith("/") ? path.substring(1) : path;
            path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            
            if (path.isEmpty()) {
                rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
                SecurityContext.setResourceContext(rcontext);
                return rcontext;
            }
            
            String[] segments = path.split("/");
            int segmentCount = segments.length;
            
            // Pattern 1: /area/domain/action (3 segments) - PRIMARY
            if (segmentCount == 3) {
                String area = segments[0];
                String functionalDomain = segments[1];
                String action = segments[2];
                
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain(functionalDomain)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                
                if (Log.isDebugEnabled()) {
                    Log.debugf("Resource Context set from path (3 segments): area=%s, domain=%s, action=%s", 
                        area, functionalDomain, action);
                }
                return rcontext;
            }
            
            // Pattern 2: /area/domain/action/id (4 segments) - ACTION with resource ID
            if (segmentCount == 4) {
                String area = segments[0];
                String functionalDomain = segments[1];
                String action = segments[2];
                String resourceId = segments[3];
                
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain(functionalDomain)
                        .withResourceId(resourceId)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                
                if (Log.isDebugEnabled()) {
                    Log.debugf("Resource Context set from path (4 segments): area=%s, domain=%s, action=%s, id=%s", 
                        area, functionalDomain, action, resourceId);
                }
                return rcontext;
            }
            
            // Pattern 3: /area/domain (2 segments) - Infer action from HTTP method
            if (segmentCount == 2) {
                String area = segments[0];
                String functionalDomain = segments[1];
                String action = inferActionFromHttpMethod(requestContext.getMethod());
                
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain(functionalDomain)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                
                if (Log.isDebugEnabled()) {
                    Log.debugf("Resource Context set from path (2 segments): area=%s, domain=%s, action=%s (inferred)", 
                        area, functionalDomain, action);
                }
                return rcontext;
            }
            
            // Pattern 4: /area (1 segment) - Minimal context
            if (segmentCount == 1) {
                String area = segments[0];
                String action = inferActionFromHttpMethod(requestContext.getMethod());
                
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain("*")
                        .build();
                SecurityContext.setResourceContext(rcontext);
                
                if (Log.isDebugEnabled()) {
                    Log.debugf("Resource Context set from path (1 segment): area=%s, action=%s (inferred)", 
                        area, action);
                }
                return rcontext;
            }
            
            // Pattern 5: More than 4 segments - Use first 3, log warning
            if (segmentCount > 4) {
                Log.warnf("Path has %d segments, using first 3 for resource context: %s", segmentCount, path);
                String area = segments[0];
                String functionalDomain = segments[1];
                String action = segments[2];
                String resourceId = segmentCount > 3 ? segments[3] : null;
                
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain(functionalDomain)
                        .withResourceId(resourceId)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                
                if (Log.isDebugEnabled()) {
                    Log.debugf("Resource Context set from path (%d segments, truncated): area=%s, domain=%s, action=%s", 
                        segmentCount, area, functionalDomain, action);
                }
                return rcontext;
            }
            
            // Fallback: Anonymous context
            Log.debugf("Non-conformant path: %s (segments: %d) - setting to anonymous context", path, segmentCount);
            rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
            SecurityContext.setResourceContext(rcontext);
            return rcontext;
        } else {
            return SecurityContext.getResourceContext().get();
        }
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
                throw new IllegalArgumentException(String.format(
                    "The realm override %s is not a configured Realm RefName", realm));
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

    private PrincipalContext applyRealmOverride(PrincipalContext context, String realm) {
        return context;
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
