package com.e2eq.framework.rest.filters;


import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.Role;
import com.e2eq.framework.securityrules.RuleContext;
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
import org.jboss.logging.Logger;

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
             Determine the resource context from the path
             */
            String path = requestContext.getUriInfo().getPath();
            StringTokenizer tokenizer = new StringTokenizer(path, "/");

            String area;
            String functionalDomain;
            String action;
            int tokenCount = tokenizer.countTokens();

            if (tokenCount > 3) {
                if (Log.isEnabled(Logger.Level.WARN)) {
                    Log.debugf("Path: %s has  more than 3 levels", path );
                }
                area = tokenizer.nextToken();
                functionalDomain = tokenizer.nextToken();
                action = tokenizer.nextToken();

                if (Log.isDebugEnabled()) {
                    Log.debugf("Based upon request convention assumed that the area is:%s  functional domain is:%s  and action is:%s", area, functionalDomain, action);
                }

                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain(functionalDomain)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                if (Log.isDebugEnabled())
                    Log.debug("Resource Context set");

            } else if (tokenCount == 3) {
                functionalDomain = tokenizer.nextToken();
                action = tokenizer.nextToken();
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(functionalDomain)
                        .withFunctionalDomain(functionalDomain)
                        .build();
                SecurityContext.setResourceContext(rcontext);

                if (Log.isDebugEnabled()) {
                    Log.debug("Resource Context set");
                }

            } else {
                Log.debugf("Non conformant path:%s could not set resource context as a result, expecting /area/functionalDomain/action: TokenCount:%s -- setting to an anonymous context", path, tokenCount);
                rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
            }
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

        Log.debugf("Running impersonation filter script:%s for user:%s, userId:%s, realm:%s", script, subject, userId, realm);

        // Context c = Context.newBuilder().allowAllAccess(true).build();
        Context c = Context.newBuilder().allowAllAccess(true).build();
        c.getBindings("js").putMember("subject", subject);
        c.getBindings("js").putMember("userId", userId);
        c.getBindings("js").putMember("realm", realm);

        boolean allow = c.eval("js", script).asBoolean();
        Log.debugf("returning allow: %s", allow ? "true" : "false");
        return allow;

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

    private String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential) {
        Set<String> rolesSet =  new HashSet<>();
        if (identity != null) {
           // ensure there are no null or empty strings in the roles array
           for (String role : identity.getRoles()) {
               if (role != null && !role.isEmpty()) {
                   rolesSet.add(role);
               }
           }
        }

        // get the set of roles from the credential if provided
        if (credential != null && credential.getRoles() != null && credential.getRoles().length > 0) {
            rolesSet.addAll(Arrays.asList(credential.getRoles()));
        }

       // look at the userProfile associated with the credential.  If so find what user groups the userProfile is a part of
       // and union all the roles together to get the effective role
        if (credential != null) {
            Optional<UserProfile> userProfile = userProfileRepo.getBySubject(credential.getSubject());
            if (userProfile.isPresent()) {
                List<UserGroup> userGroups = userGroupRepo.findByUserProfileRef(userProfile.get().createEntityReference());
                if (!userGroups.isEmpty()) {
                    userGroups.forEach(userGroup -> rolesSet.addAll(userGroup.getRoles().stream().map(Role::toString).toList()));
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
