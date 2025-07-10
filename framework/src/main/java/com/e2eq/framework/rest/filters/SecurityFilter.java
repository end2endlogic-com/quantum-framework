package com.e2eq.framework.rest.filters;


import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.ValidateUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.graalvm.polyglot.Context;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;


@Provider
//@PreMatching
//@PermissionCheck
public class SecurityFilter implements ContainerRequestFilter {

    private static final String AUTHENTICATION_SCHEME = "Bearer";

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    JsonWebToken jwt;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    RuleContext ruleContext;

    @Inject
    RealmRepo realmRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    ObjectMapper mapper;

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


    protected ResourceContext determineResourceContext(ContainerRequestContext requestContext) {

        if (!SecurityContext.getResourceContext().isPresent()) {
            ResourceContext rcontext = null;

            /**
             Determine the resource context from the path
             */
            String path = requestContext.getUriInfo().getPath();
            StringTokenizer tokenizer = new StringTokenizer(path, "/");

            String area;
            String functionalDomain;
            String action;
            int tokenCount = tokenizer.countTokens();

            if (tokenCount > 2) {
                if (Log.isEnabled(Logger.Level.WARN)) {
                    Log.warn("Path: +" + path + " three or more levels");
                }
                area = tokenizer.nextToken();
                functionalDomain = tokenizer.nextToken();
                action = tokenizer.nextToken();

                if (Log.isDebugEnabled()) {
                    Log.debug("Based upon request convention assumed that the area is:" + area + " functional domain is:" + functionalDomain + " and action is:" + action);
                }

                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(area)
                        .withFunctionalDomain(functionalDomain)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                if (Log.isDebugEnabled())
                    Log.debug("Resource Context set");

            } else if (tokenCount == 2) {
                functionalDomain = tokenizer.nextToken();
                action = tokenizer.nextToken();
                rcontext = new ResourceContext.Builder()
                        .withAction(action)
                        .withArea(functionalDomain)
                        .withFunctionalDomain(functionalDomain)
                        .build();
                SecurityContext.setResourceContext(rcontext);
                if (Log.isEnabled(Logger.Level.WARN)) {
                    Log.warnf( "%s:Odd request convention, not following /area/fd/fa .. so assuming the fd and area are equal: %s only two tokens for resource, assuming area as fd, fd=%s action=%s" , path, functionalDomain, functionalDomain, action);
                }

                if (Log.isDebugEnabled()) {
                    Log.debug("Resource Context set");
                }

            } else {
                Log.warn("Non conformant url:" + path + " could not set resource context as a result, expecting /area/functionalDomain/action: TokenCount:" + tokenCount);
                rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
            }
            return rcontext;
        } else {
            return SecurityContext.getResourceContext().get();
        }
    }

    boolean runScript(String username, String userId, String realm, String script) {
        Objects.requireNonNull(username, "Username cannot be null");
        Objects.requireNonNull(userId, "UserId cannot be null");
        Objects.requireNonNull(realm, "Realm cannot be null");
        Objects.requireNonNull(script, "Script cannot be null");


        // Context c = Context.newBuilder().allowAllAccess(true).build();
        Context c = Context.newBuilder().allowAllAccess(true).build();
        c.getBindings("js").putMember("username", username);
        c.getBindings("js").putMember("userId", userId);
        c.getBindings("js").putMember("realm", realm);

        boolean allow = c.eval("js", script).asBoolean();
        return allow;

    }

    protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {

        String impersonateUsername = requestContext.getHeaderString("X-Impersonate-Username");
        String impersonateUserId = requestContext.getHeaderString("X-Impersonate-UserId");
        boolean impersonate = false;

        if (impersonateUsername != null && impersonateUserId != null) {
            throw new IllegalArgumentException(String.format("Impersonation Username: %s and impersonate UserId:%s can only pass one of them but not both",
                    impersonateUsername, impersonateUserId));
        } else if (impersonateUsername != null) {
            impersonate = true;
        } else if (impersonateUserId != null) {
            impersonate = true;
        }

        if (Log.isDebugEnabled()) {
            Log.debug("---Determining principal context--");
            Log.debugf("Security Identity:%s" , securityIdentity.toString());
            Log.debugf("Security Identity Principal Name:%s " ,securityIdentity.getPrincipal().getName());

            if (impersonateUsername!= null) {
                Log.debugf("Impersonating user, X-Impersonate-Username: %s present", impersonateUsername );
            } else if (impersonateUserId != null) {
                Log.debugf("Impersonating user, X-Impersonate-UserId header present %s", impersonateUserId);
            } else {
                Log.debug("No impersonation header present");
            }
        }

       /* MultivaluedMap<String, String> queryParams = requestContext.getUriInfo().getQueryParameters();
        String realm = (queryParams.get("realm")==null) ? null : queryParams.get("realm").get(0);
        if (realm != null ) {
            Log.debugf("!!!! Determining realm from query parameters:%s ", realm);
        } */
        String realm = requestContext.getHeaderString("X-Realm");
        if (realm != null) {
            Log.debugf("!!! Determining realm from X-Realm header:%s ", realm);
        } else {
            Log.debug("No realm header override present");
        }

        // Default to an anonymous PrincipalContext
        PrincipalContext pcontext;

        if (realm == null ) {
            Log.debug("Defaulting to anonymous with system realm default");
            pcontext = new PrincipalContext.Builder()
                          .withDefaultRealm(securityUtils.getSystemRealm())
                          .withDataDomain(securityUtils.getSystemDataDomain())
                          .withUserId(securityUtils.getAnonymousUserId())
                          .withRoles(new String[]{"ANONYMOUS"})
                          .withScope("systemGenerated")
                          .build();
        }
        else {
            Log.debugf("Defaulting to anonymous with %s realm", realm);
            pcontext = new PrincipalContext.Builder()
                          .withDefaultRealm(realm)
                          .withDataDomain(securityUtils.getSystemDataDomain())
                          .withUserId(securityUtils.getAnonymousUserId())
                          .withRoles(new String[]{"ANONYMOUS"})
                          .withScope("systemGenerated")
                          .build();
        }

        // Get the Authorization header from the request
        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        // if there is an authorization header then we can authenticate this call.
        if (authorizationHeader != null && jwt != null) {
            String token = authorizationHeader.substring(AUTHENTICATION_SCHEME.length()).trim();
            String username = jwt.getClaim("username");

            if (username == null) {
                Log.warn("JWT did not contain a username claim, using sub claim instead");
                username = jwt.getClaim("sub");
            } else {
                Log.debugf("Found user with username %s in the claims", username);
            }
            if (impersonate) {
                //TODO check if the user that is impersonating another user is authorized to
                Log.warnf("Impersonating user %s with userId %s from username %s, authorization check not implemented!!", impersonateUsername, impersonateUserId, username);

            }

            if (username != null) {
                Optional<CredentialUserIdPassword> ocreds = (realm == null ) ? credentialRepo.findByUsername(username, securityUtils.getSystemRealm()) : credentialRepo.findByUsername(username, realm);

                if (ocreds.isPresent()) {
                    Log.debugf("Found user with username %s userId:%s in the database, adding roles %s", username, ocreds.get().getUserId(), Arrays.toString(ocreds.get().getRoles()));
                    CredentialUserIdPassword creds = ocreds.get();

                    if (impersonate && creds.getImpersonateFilter() == null) {
                        throw new IllegalArgumentException(String.format("username %s with userId %s is not configured with a impersonateFilter in realm:%s", creds.getUsername(), creds.getUserId(), credentialRepo.getDatabaseName()));
                    } else if (impersonate) {
                        if (!runScript(username, ocreds.get().getUserId(), (realm== null ) ? credentialRepo.getDatabaseName() : realm, creds.getImpersonateFilter())) {
                            throw new WebApplicationException(String.format("User %s with userId %s is not authorized to impersonate user %s with userId %s in realm:%s", username, ocreds.get().getUserId(), impersonateUsername, impersonateUserId, (realm== null ) ? credentialRepo.getDatabaseName() : realm), Response.Status.FORBIDDEN);
                        }
                    }

                    if (impersonate) {
                        Optional<CredentialUserIdPassword> oicreds;
                        if (impersonateUsername != null) {
                             oicreds =(realm == null) ? credentialRepo.findByUsername(impersonateUsername, securityUtils.getSystemRealm()) : credentialRepo.findByUsername(impersonateUsername, realm);
                        } else if (impersonateUserId!= null) {
                             oicreds = (realm == null ) ? credentialRepo.findByUserId(impersonateUserId, securityUtils.getSystemRealm()) : credentialRepo.findByUserId(impersonateUserId, realm)  ;
                        } else
                        {
                            throw new IllegalStateException("Logic error on server side impersonating user, neither X-Impersonate-Username nor X-Impersonate-UserId header is present yet impersonate is true?");
                        }

                        if (!oicreds.isPresent()) {
                            throw new WebApplicationException( String.format("Could not find impersonated userId or username, id:%s", (impersonateUsername == null) ? impersonateUserId : impersonateUsername), Response.Status.NOT_FOUND);
                        }
                        pcontext.setUserId(oicreds.get().getUserId());
                        String[] roles = oicreds.get().getRoles();
                        if (roles == null || roles.length == 0) {
                            Set<String> rolesSet = securityIdentity.getRoles();
                            roles = rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[0]);
                        } else {
                            Set<String> roleSet = new HashSet<String> () ;
                            roleSet.addAll(securityIdentity.getRoles());
                            roleSet.addAll(Arrays.asList(roles));
                            roles = roleSet.toArray(new String[0]);
                        }
                        DataDomain dataDomain = creds.getDomainContext().toDataDomain(oicreds.get().getUserId());
                        pcontext = new PrincipalContext.Builder()
                                      .withDefaultRealm(oicreds.get().getDomainContext().getDefaultRealm())
                                      .withDataDomain(dataDomain)
                                      .withUserId(oicreds.get().getUserId())
                                      .withRoles(roles)
                                      .withScope("AUTHENTICATED")
                                      .withImpersonatedByUsername(oicreds.get().getUsername())
                                      .withImpersonatedByUserId(oicreds.get().getUserId())
                                      .build();

                        if (Log.isDebugEnabled()) {
                            Log.debugf("Principal Context updated with roles: %s", Arrays.toString(roles));
                            Log.debugf("Principal Context: %s", pcontext.toString());
                        }

                    } else {
                        pcontext.setUserId(creds.getUserId());
                        String[] roles = creds.getRoles();
                        if (roles == null || roles.length == 0) {
                            Set<String> rolesSet = securityIdentity.getRoles();
                            roles = rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[0]);
                        }else {
                            Set<String> roleSet = new HashSet<String>();
                            roleSet.addAll(securityIdentity.getRoles());
                            roleSet.addAll(Arrays.asList(roles));
                            roles = roleSet.toArray(new String[0]);
                        }
                        DataDomain dataDomain = creds.getDomainContext().toDataDomain(creds.getUserId());
                        pcontext = new PrincipalContext.Builder()
                                      .withDefaultRealm(creds.getDomainContext().getDefaultRealm())
                                      .withDataDomain(dataDomain)
                                      .withUserId(creds.getUserId())
                                      .withRoles(roles)
                                      .withScope("AUTHENTICATED")
                                      .build();
                        if (Log.isDebugEnabled()) {
                            Log.debugf("Principal Context updated with roles: %s", Arrays.toString(roles));
                            Log.debugf("Principal Context: %s", pcontext.toString());
                        }
                    }
                } else {
                    // we did not find the user in the database lets see if we can find a realm based on email address
                    Log.warnf("Could not find user with username: %s in realm %s", username,( realm == null) ? securityUtils.getSystemRealm() : realm );
                    Log.warn("Attempting to see if the realm is defined via the user/subject being an email address");
                    if (ValidateUtils.isValidEmailAddress(username)) {

                        String emailDomain = username.substring(username.indexOf("@") + 1);
                        Log.infof("UserId appears to be an email address with domain %s searching realms for domain Context", emailDomain);
                        Optional<Realm> orealm = realmRepo.findByEmailDomain(emailDomain, true, securityUtils.getSystemRealm());
                        if (orealm.isPresent()) {
                            Realm rrealm = orealm.get();
                            DataDomain dataDomain = rrealm.getDomainContext().toDataDomain(username);
                            Set<String> rolesSet = securityIdentity.getRoles();
                            String[] roles = rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[rolesSet.size()]);
                            pcontext = new PrincipalContext.Builder()
                                          .withDefaultRealm(rrealm.getDomainContext().getDefaultRealm())
                                          .withDataDomain(dataDomain)
                                          .withUserId(username)
                                          .withRoles(roles)
                                          .withScope("AUTHENTICATED")
                                          .build();
                        } else {
                            String errorText  = String.format("Could not find the username:%s in the database:%s and could not find a realm based on the email domain:%s", username, credentialRepo.getDatabaseName(), emailDomain);
                            Log.warnf(errorText);
                            throw new WebApplicationException(errorText, Response.Status.UNAUTHORIZED);
                        }
                    } else {
                        String errorText = String.format("Could not find the user with username:%s in the database:%s and could not parse the id into an email address to look up the realm.", username, (realm==null) ? credentialRepo.getDatabaseName() : realm);
                        Log.warnf(errorText);
                        throw new WebApplicationException(errorText, Response.Status.UNAUTHORIZED);
                    }
                    // we could not find the userid and we could not parse the id into an email address to look up the realm.
                    // so all we can do is assume the system defaults and see if there are roles defined

                    Set<String> rolesSet = securityIdentity.getRoles();
                    String[] roles = rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[0]);
                    pcontext.setRoles(roles);
                }
            }
        }
        // either the context was set or we defaulted to the anonmyous context


        return pcontext;
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
