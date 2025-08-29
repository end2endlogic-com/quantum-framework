package com.e2eq.framework.rest.filters;


import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.ValidateUtils;
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

    boolean runScript(String subject, String userId, String realm, String script) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(userId, "UserId cannot be null");
        Objects.requireNonNull(realm, "Realm cannot be null");
        Objects.requireNonNull(script, "Script cannot be null");

        Log.debugf("Running impersonation filter script:%s for user:%s, userId:%s, realm:%s", script, subject, userId, realm);

        // Context c = Context.newBuilder().allowAllAccess(true).build();
        Context c = Context.newBuilder().allowAllAccess(true).build();
        c.getBindings("js").putMember("username", subject);
        c.getBindings("js").putMember("userId", userId);
        c.getBindings("js").putMember("realm", realm);

        boolean allow = c.eval("js", script).asBoolean();
        Log.debugf("returning allow: %s", allow ? "true" : "false");
        return allow;

    }

    protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {

        String impersonateSubject = requestContext.getHeaderString("X-Impersonate-Subject");
        String impersonateUserId = requestContext.getHeaderString("X-Impersonate-UserId");
        String actingOnBehalfOfSubject = requestContext.getHeaderString("X-Acting-On-Behalf-Of-Subject");
        String actingOnBehalfOfUserId = requestContext.getHeaderString("X-Acting-On-Behalf-Of-UserId");
        boolean impersonate = false;

        if (impersonateSubject != null && impersonateUserId != null) {
            throw new IllegalArgumentException(String.format("Impersonation Subject: %s and impersonate UserId:%s can only pass one of them but not both",
                    impersonateSubject, impersonateUserId));
        } else if (impersonateSubject != null) {
            impersonate = true;
        } else if (impersonateUserId != null) {
            impersonate = true;
        }

        if (Log.isDebugEnabled()) {
            Log.debug("---Determining principal context--");
            Log.debugf("Security Identity:%s" , securityIdentity.toString());
            Log.debugf("Security Identity Principal Name:%s " ,securityIdentity.getPrincipal().getName());

            if (impersonateSubject!= null) {
                Log.debugf("Impersonating user, X-Impersonate-Subject: %s present", impersonateSubject );
            } else if (impersonateUserId != null) {
                Log.debugf("Impersonating user, X-Impersonate-UserId header present %s", impersonateUserId);
            } else {
                Log.debug("No impersonation header present");
            }

            if (actingOnBehalfOfSubject!= null) {
                Log.debugf("Acting on behalf of user, X-Acting-On-Behalf-Of-Subject: %s present", actingOnBehalfOfSubject );
            } else
            if(actingOnBehalfOfUserId != null) {
                Log.debugf("Acting on behalf of user, X-Acting-On-Behalf-Of-UserId header present %s", actingOnBehalfOfUserId);
            } else {
                Log.debug("No acting on behalf of user header present");
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
            String sub = jwt.getClaim("sub");

            if (sub == null) {
                throw new IllegalStateException("sub attribute not provided but is required in token claims");
            } else {
                Log.debugf("Found user with subject %s in the claims", sub);
            }
            if (impersonate) {
                Log.warnf("Impersonating user %s with userId %s from subject %s, authorization check not implemented!!", impersonateSubject, impersonateUserId, sub);
            }

            if (sub != null) {
                Optional<CredentialUserIdPassword> ocreds = (realm == null ) ? credentialRepo.findBySubject(sub, securityUtils.getSystemRealm(), true) : credentialRepo.findBySubject(sub, realm, true);
                if (!ocreds.isPresent()) {
                    String username = jwt.getClaim("username");
                    if (username != null ) {
                        // attempt to find the user by the userId
                        ocreds = (realm == null) ? credentialRepo.findByUserId(username, securityUtils.getSystemRealm(), true) : credentialRepo.findByUserId(username, realm, true);
                        if (ocreds.isPresent()) {
                            String text = String.format("Found user with userId %s but subject is:%s but token has subject:%s in the database, roles in credential is %s", username, ocreds.get().getSubject(), sub,  Arrays.toString(ocreds.get().getRoles()));
                            Log.warn(text);
                            throw new IllegalStateException(text);
                        }
                    }
                }

                if (ocreds.isPresent()) {
                    Log.debugf("Found user with subject %s userId:%s in the database, adding roles %s", sub, ocreds.get().getUserId(), Arrays.toString(ocreds.get().getRoles()));
                    Log.debugf("Found user with subject %s userId:%s in the database, adding roles %s", sub, ocreds.get().getUserId(), Arrays.toString(ocreds.get().getRoles()));
                    CredentialUserIdPassword creds = ocreds.get();
                    String contextRealm = (realm == null ) ? creds.getDomainContext().getDefaultRealm() : realm;


                    if (impersonate && creds.getImpersonateFilterScript() == null) {
                        throw new IllegalArgumentException(String.format("subject %s with userId %s is not configured with a impersonateFilter in realm:%s", creds.getSubject(), creds.getUserId(), credentialRepo.getDatabaseName()));
                    } else if (impersonate) {
                        if (!runScript(sub, ocreds.get().getUserId(), (realm== null ) ? credentialRepo.getDatabaseName() : realm, creds.getImpersonateFilterScript())) {
                            throw new WebApplicationException(String.format("User %s with userId %s is not authorized to impersonate user %s with userId %s in realm:%s", sub, ocreds.get().getUserId(), impersonateSubject, impersonateUserId, (realm== null ) ? credentialRepo.getDatabaseName() : realm), Response.Status.FORBIDDEN);
                        }
                    }

                    if (realm != null ) {
                        // check the creds to see if your allowed to go to this realm
                        if (!matchesRealmFilter(realm, creds.getRealmRegEx())) {
                            throw new WebApplicationException(String.format("User %s with userId %s is not authorized to access realm:%s realm filter:%s", sub, ocreds.get().getUserId(), realm, ocreds.get().getRealmRegEx() == null ? "null" : "'" + ocreds.get().getRealmRegEx())+ "'" , Response.Status.FORBIDDEN);
                        }
                    }


                    if (impersonate) {
                        Optional<CredentialUserIdPassword> oicreds;
                        if (impersonateSubject != null) {
                             oicreds =(realm == null) ? credentialRepo.findBySubject(impersonateSubject, securityUtils.getSystemRealm(), true) : credentialRepo.findBySubject(impersonateSubject, realm, true);
                        } else if (impersonateUserId!= null) {
                             oicreds = (realm == null ) ? credentialRepo.findByUserId(impersonateUserId, securityUtils.getSystemRealm(), true) : credentialRepo.findByUserId(impersonateUserId, realm, true)  ;
                        } else
                        {
                            throw new IllegalStateException("Logic error on server side impersonating user, neither X-Impersonate-Subject nor X-Impersonate-UserId header is present yet impersonate is true?");
                        }

                        if (!oicreds.isPresent()) {
                            throw new WebApplicationException( String.format("Could not find impersonated userId or subject, id:%s", (impersonateSubject == null) ? impersonateUserId : impersonateSubject), Response.Status.NOT_FOUND);
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
                                      .withImpersonatedBySubject(oicreds.get().getSubject())
                                      .withImpersonatedByUserId(oicreds.get().getUserId())
                                      .withActingOnBehalfOfUserId(actingOnBehalfOfUserId)
                                      .withActingOnBehalfOfSubject(actingOnBehalfOfSubject)
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
                }
            }
        }
        // either the context was set or we defaulted to the anonymous context


        return pcontext;
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
