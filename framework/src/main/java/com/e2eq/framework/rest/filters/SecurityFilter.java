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
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;


@Provider
//@PreMatching
//@PermissionCheck
public class SecurityFilter implements ContainerRequestFilter {

    private static final String AUTHENTICATION_SCHEME = "Bearer";

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    JWTParser parser;

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
    UserProfileRepo userProfileRepo;

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

        if (Log.isDebugEnabled()) {
            String path = requestContext.getUriInfo().getPath();
            String queryParams = requestContext.getUriInfo().getQueryParameters().toString();
            String method = requestContext.getMethod();
            String body = "";
            // here for debug purposes if you enable this you will break media upload types like multipart/form-data
            if ("POST".equalsIgnoreCase(method) && false) {
                // Buffer the entity stream
                BufferedInputStream bufferedInputStream = new BufferedInputStream(requestContext.getEntityStream());
                String rawBody = new BufferedReader(new InputStreamReader(bufferedInputStream))
                        .lines().collect(Collectors.joining("\n"));

                // Reset the entity stream so it can be read again by downstream filters
                requestContext.setEntityStream(new ByteArrayInputStream(rawBody.getBytes(StandardCharsets.UTF_8)));

                // Pretty print JSON
                try {
                    Object json = mapper.readValue(rawBody, Object.class);
                    ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
                    body = writer.writeValueAsString(json);
                } catch (Exception e) {
                    body = rawBody; // Fallback to raw body if not JSON
                }
                if (Log.isDebugEnabled() && !requestContext.getUriInfo().getPath().contains("login"))
                    Log.debugf("SecurityFilter: %s %s?%s body[%s]", method, path, queryParams, body);
                else
                if  (Log.isDebugEnabled())
                    Log.debugf("SecurityFilter: %s %s?%s", method, path, queryParams);
            }




        }

        ResourceContext resourceContext = determineResourceContext(requestContext);
        PrincipalContext principalContext = determinePrincipalContext(requestContext);
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

    protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
        if (Log.isDebugEnabled()) {
            Log.debug("---Determining principal context--");
            Log.debug("Security Identity:" + securityIdentity.toString());
        }

        // Default to an anonymous PrincipalContext
        PrincipalContext pcontext = new PrincipalContext.Builder()
                .withDefaultRealm(securityUtils.getSystemRealm())
                .withDataDomain(securityUtils.getSystemDataDomain())
                .withUserId(securityUtils.getAnonymousUserId())
                .withRoles(new String[]{"ANONYMOUS"})
                .withScope("systemGenerated")
                .build();

        // Get the Authorization header from the request
        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        // if there is an authorization header then we can authenticate this call.
        if (authorizationHeader != null && jwt != null) {
            String token = authorizationHeader.substring(AUTHENTICATION_SCHEME.length()).trim();
            String userId = jwt.getClaim("username");

            if (userId == null) {
                Log.warn("JWT did not contain a username claim, using sub claim instead");
                userId = jwt.getClaim("sub");
            }

            if (userId != null) {
                Optional<CredentialUserIdPassword> ocreds = credentialRepo.findByUserId(userId);
                if (ocreds.isPresent()) {
                    CredentialUserIdPassword creds = ocreds.get();
                    String[] roles = creds.getRoles();
                    if (roles == null || roles.length == 0) {
                        Set<String> rolesSet = securityIdentity.getRoles();
                        roles = rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[0]);
                    }
                    DataDomain dataDomain = creds.getDomainContext().toDataDomain(userId);
                    pcontext = new PrincipalContext.Builder()
                            .withDefaultRealm(creds.getDomainContext().getDefaultRealm())
                            .withDataDomain(dataDomain)
                            .withUserId(userId)
                            .withRoles(roles)
                            .withScope("AUTHENTICATED")
                            .build();
                } else {
                    // we did not find the user in the database lets see if we can find a realm based on email address
                    Log.warnf("Could not find user with username / subject:%s", userId);
                    Log.warn("Attempting to see if the realm is defined via the user/subject being an email address");
                    if (ValidateUtils.isValidEmailAddress(userId)) {

                        String emailDomain = userId.substring(userId.indexOf("@") + 1);
                        Log.infof("UserId appears to be an email address with domain %s searching realms for domain Context", emailDomain);
                        Optional<Realm> orealm = realmRepo.findByEmailDomain(emailDomain, true);
                        if (orealm.isPresent()) {
                            Realm realm = orealm.get();
                            DataDomain dataDomain = realm.getDomainContext().toDataDomain(userId);
                            Set<String> rolesSet = securityIdentity.getRoles();
                            String[] roles = rolesSet.isEmpty() ? new String[]{"ANONYMOUS"} : rolesSet.toArray(new String[rolesSet.size()]);
                            pcontext = new PrincipalContext.Builder()
                                          .withDefaultRealm(realm.getDomainContext().getDefaultRealm())
                                          .withDataDomain(dataDomain)
                                          .withUserId(userId)
                                          .withRoles(roles)
                                          .withScope("AUTHENTICATED")
                                          .build();
                        } else {
                            Log.warnf("Could not find the user:%s in the database:%s and could not find a realm based on the email domain:%s", userId, credentialRepo.getDatabaseName(), emailDomain);
                        }
                    } else {
                        Log.warnf("Could not find the user:%s in the database:%s and could not parse the id into an email address to look up the realm.", userId, credentialRepo.getDatabaseName());
                    }
                    // we could not find the userid and we could not parse the id into an email address to look up the realm.
                    // so all we can do is assume the system defaults and see if there are roles defined
                    pcontext.setUserId(userId);
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
