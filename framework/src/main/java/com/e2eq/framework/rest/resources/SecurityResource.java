package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import com.e2eq.framework.rest.models.Role;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.rest.models.AuthResponse;
import com.e2eq.framework.rest.models.RegistrationRequest;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;

import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.ApplicationRegistrationRequestRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.rest.responses.RestSecurityError;

import com.e2eq.framework.util.TokenUtils;
import com.e2eq.framework.util.ValidateUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@OpenAPIDefinition(
        tags = {
                @Tag(name = "area", description = "Security")
        },
        info = @Info(
                title = "Core Auth Security API",
                version = "0.0.1"
        )
)
@Path("/security")
@Tag(name = "security", description = "Operations related to security")
@RequestScoped
public class SecurityResource {
    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    ApplicationRegistrationRequestRepo registrationRepo;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;


    @Inject
    JsonWebToken jwt;

    @Inject
    Validator validator;

    @Inject
    JWTParser parser;

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    protected String issuer;

    @ConfigProperty(name = "com.b2bi.jwt.duration")
    protected long tokenDuration;

    @Path("register")
    @PermitAll
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@Context UriInfo uirInfo, @NotNull RegistrationRequest registrationRequest) {

        Set<ConstraintViolation<RegistrationRequest>> violations = validator.validate(registrationRequest);

        if (!violations.isEmpty()) {
            RestError error = RestError.builder().build();
            StringWriter writer = new StringWriter();
            violations.forEach(violation -> {
                writer.append(violation.getMessage());
                writer.append("\n");
            });

            error.setStatus(Response.Status.BAD_REQUEST.getStatusCode());
            error.setStatusMessage(writer.toString());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        // check if registration request has already been made?
        Optional<ApplicationRegistration> oRegRequest = registrationRepo.findByRefName(registrationRequest.getEmail());
        if (oRegRequest.isPresent()) {
            ApplicationRegistration regRequest = oRegRequest.get();
            RestError error = RestError.builder()
                    .statusMessage("a registration request with the email address: " + regRequest.getUserId() + " is already in the system with the status:" + regRequest.getStatus() + " contact help@b2bintegrator.com for assistance")
                .status(Response.Status.BAD_REQUEST.getStatusCode()).build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        // create the application registration request
        ApplicationRegistration registration = new ApplicationRegistration();
        registration.setRefName(registrationRequest.getEmail());
        registration.setUserId(registrationRequest.getEmail());

        // Validate the emailAddress
        if (!ValidateUtils.isValidEmailAddress(registrationRequest.getEmail())) {
            RestError error = RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
            .statusMessage("Email:" + registrationRequest.getEmail() + " is not a valid email address").build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
        registration.setUserEmail(registrationRequest.getEmail());
        // Parse the email to get the company identifier
        String email = registrationRequest.getEmail();
        String secondHalf = email.split("@")[1];
        String identifier = secondHalf.split("\\.")[0];

        Optional<ApplicationRegistration> eregistration = registrationRepo.findByCompanyIdentifier(identifier);
        if (eregistration.isPresent()) {
            RestError error =RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
            .statusMessage("Registration request for domain:" + identifier + " has already been made by:" + eregistration.get().getUserEmail() + " and is the state:" + eregistration.get().getStatus()).build();
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        registration.setCompanyName(registrationRequest.getCompanyName());
        registration.setCompanyIdentifier(identifier);
        registration.setTerms(registrationRequest.isAcceptedTerms());
        registration.setPassword(registrationRequest.getPassword());
        registration.setFname(registrationRequest.getFname());
        registration.setLname(registrationRequest.getLname());
        registration.setUserTelephone(registrationRequest.getTelephone());

        registration = registrationRepo.save(registration);
        return Response.ok().entity(registration).status(Response.Status.CREATED).build();
    }

    @Path("register")
    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(@QueryParam("refName") String refName) {
        Response response;
        Optional<ApplicationRegistration> opRegRequest = registrationRepo.findByRefName(refName);

        if (opRegRequest.isPresent()) {
            response = Response.ok(opRegRequest.get()).build();
        } else {
            RestError error = RestError.builder()
                    .status(Response.Status.NOT_FOUND.getStatusCode())
                    .statusMessage("RefName:" + refName + " was not found")
                    .build();

            response = Response.status(Response.Status.NOT_FOUND).entity(error).build();
        }

        return response;
    }


    @Path("me")
    @Authenticated
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response me(@Context SecurityContext securityContext) {
        if (Log.isInfoEnabled())
            Log.info("me: - UserId:" + securityContext.getUserPrincipal().getName());

        try {
            Optional<UserProfile> userProfileOp = userProfileRepo.getByUserId(securityContext.getUserPrincipal().getName());
            if (userProfileOp.isPresent()) {
                userProfileRepo.fillUIActions(userProfileOp.get());
            }
            Response response;
            if (userProfileOp.isPresent()) {
                response = Response.ok(userProfileOp.get()).build();
                return response;
            } else {
                RestError error = RestError.builder()
                        .statusMessage("Could not find userId:" + securityContext.getUserPrincipal().getName() + " please register")
                        .status(Response.Status.NOT_FOUND.getStatusCode())
                        .debugMessage("User could not be found, indicating that the user has not registered in the past")
                        .build();
                return Response.status(Response.Status.NOT_FOUND).entity(error).build();
            }
        } catch (SecurityCheckException e) {
            RestSecurityError error = RestSecurityError.builder()
                    .statusMessage("The user id is not authorized to read the user profile required for login, due to a permission configuration error")
                    .status(Response.Status.UNAUTHORIZED.getStatusCode())
                    .securityResponse(e.getResponse())
                    .build();
            return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
        }
    }


    @POST
    @PermitAll
    @Path("/login")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    //  @APIResponse(responseCode="403", description="The credentials provided did not match"))
    //  @APIResponse(responseCode="200", description"successful"))
    public Response login(@Context HttpHeaders headers, AuthRequest authRequest) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        Response rc;

        String remoteAddress = headers.getRequestHeaders().getFirst("X-FORWARDED_FOR");
        if (remoteAddress == null) {
            remoteAddress = headers.getRequestHeaders().getFirst("Host");
        }
        String userAgent = headers.getRequestHeaders().getFirst("User-Agent");

        if (Log.isInfoEnabled())
            Log.info("Authentication Attempt:" + authRequest.getUserId() + " Address:" + ((remoteAddress != null) ? remoteAddress : "unknown") + " UserAgent:" + ((userAgent != null) ? userAgent : "unknown"));

        StringTokenizer tokenizer = new StringTokenizer(authRequest.getUserId(), "@");
        String user = tokenizer.nextToken();
        String tenantId = tokenizer.nextToken().replace(".", "-");

        if (Log.isDebugEnabled()) {
            Log.debug("Logging in userid:" + user + " with tenantId:" + tenantId);
        }

        var authProvider = authProviderFactory.getAuthProvider();


        try {
            AuthProvider.LoginResponse loginResponse = authProvider.login(authRequest.getUserId(), authRequest.getPassword());
            if (loginResponse.authenticated()) {
                return Response.ok(new AuthResponse(loginResponse.positiveResponse().accessToken(), loginResponse.positiveResponse().refreshToken())).build();
            }
            else {
                RestError error = RestError.builder()
                        .statusMessage(loginResponse.negativeResponse().errorMessage())
                        .status(Response.Status.UNAUTHORIZED.getStatusCode())
                        .build();
                return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
            }

        } catch (SecurityException e) {
            RestError error = RestError.builder()
                    .statusMessage(e.getMessage())
                    .status(Response.Status.UNAUTHORIZED.getStatusCode())
                    .build();
            return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
        }


        //return Response.ok(Map.of(
        //        "accessToken", loginResponse.accessToken(),
        //        "refreshToken", loginResponse.refreshToken()
        //)).build();

        /* Optional<CredentialUserIdPassword> creds = credentialRepo.findByUserId(tenantId,
                authRequest.getUserId(), true);

        if (creds.isPresent() && EncryptionUtils.checkPassword(authRequest.getPassword(), creds.get().getPasswordHash())) {
            // Get the password hash
            AuthResponse response = generateAuthResponse(creds.get().getUserId(),
                    creds.get().getDataDomain().getTenantId(),
                    creds.get().getDefaultRealm(),
                    creds.get().getArea2RealmOverrides(),
                    creds.get().getDataDomain().getOrgRefName(),
                    creds.get().getDataDomain().getAccountNum(),
                    creds.get().getRoles(),
                    tokenDuration,
                    tokenDuration + 3200l,
                    issuer);

            rc = Response.ok(
                    response
            ).build();
        } else {
            if (Log.isInfoEnabled())
                Log.info("Login failed for userId:" + authRequest.getUserId());
            RestError error =RestError.builder().build();
            error.setStatusMessage("Either the user id was not found or the password did not match, check credentials and try again. Are you registered? ");
            error.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
            error.setReasonMessage("Could not find credential combination");
            rc = Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
        }

        return rc;
         */
    }

    protected AuthResponse generateAuthResponse(@NotNull (message = "userId for generating auth response can not be null") String userId,
                                             @NotNull (message = "the roles array can not be null for generating an auth response") String[] roles,
                                             long durationInSeconds,
                                             long incrementRefreshDuration,
                                             @NotNull(message = "the issuer can not be null for for generating an auth response") String issuer) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String userToken = TokenUtils.generateUserToken(
                userId,
                new HashSet<>(Arrays.asList(roles)),
                durationInSeconds,
                issuer);

        String refreshToken = TokenUtils.generateRefreshToken(
                userId,
                durationInSeconds + incrementRefreshDuration,
                issuer);

        return new AuthResponse(userToken, refreshToken);
    }

    @POST
    @Authenticated
    @Path("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response refresh(AuthResponse refreshRequest) throws Exception {
        if (Log.isEnabled(Logger.Level.WARN))
            Log.warn(">> REFRESH TOKEN:" + refreshRequest.getRefresh_token());
        JsonWebToken jwt = parser.parse(refreshRequest.getRefresh_token());

        // ensure correct scope
        if (!jwt.getClaim("scope").equals(TokenUtils.REFRESH_SCOPE)) {
            RestError error = RestError.builder().build();
            error.setStatusMessage("Token is not valid, it has an invalid scope:" + jwt.getClaim("scope"));
            error.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
            //error.setReasonMessage("Could not find credential combination");
            return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
        }

        // validate the token signature?

        String[] roles = new String[jwt.getGroups().size()];
        roles = jwt.getGroups().toArray(roles);

        /** Something strange here **/
        AuthResponse response = generateAuthResponse(jwt.getSubject(),
                roles,
                tokenDuration,
                tokenDuration + 3200l,
                issuer);

        return Response.ok(response).build();
    }

    @POST
    @PermitAll
    @Path("/logout")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response logout() {
        if (Log.isInfoEnabled())
            Log.info("-Logout-");

        com.e2eq.framework.model.securityrules.SecurityContext.clear();
        return Response.ok().build();
    }

    @GET
    @PermitAll
    @Path("healthCheck")
    public Response healthCheck() {
        if (Log.isInfoEnabled())
            Log.info("-HealthCheck-");

        return Response.ok("OK").build();
    }

    @GET
    @Authenticated
    @Path("/authenticated/test")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response test(@Context SecurityContext securityContext) {

        if (Log.isInfoEnabled())
            Log.info("====== TEST CALLED ====");

       /* if (jwt.getClaimNames() != null) {
                for (String claimName: jwt.getClaimNames()) {
                    Log.info(claimName + ":" + jwt.getClaim(claimName));
                }
        } */

        if (securityContext.getUserPrincipal() == null) {
            RestError error = RestError.builder().build();
            error.setStatusMessage("The security Principal is null");
            error.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
            return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
        }

        Log.info("Security Context:");
        Log.info("   Principal Name:" + securityContext.getUserPrincipal().getName());
        Log.info("   IsSecure:" + securityContext.isSecure());
        Log.info("   Authentication Scheme:" + securityContext.getAuthenticationScheme());
        Log.info("   Is User Role:" + securityContext.isUserInRole(Role.user.toString()));
        Log.info("   Is Admin Role:" + securityContext.isUserInRole(Role.admin.toString()));

        Log.info("--- Security Identity ---");
        Log.info("  Is Anonymous:" + securityIdentity.isAnonymous());
        for (String r : securityIdentity.getRoles()) {
            Log.info("  Role:" + r);
        }
        Log.info("  Is Authenticated:" + securityIdentity.getRoles());
        Log.info("  Principal:" + securityIdentity.getPrincipal().getName());

        Date date = new Date();
        if (jwt != null) {
            Log.info("JWT:");
            Log.info("  Issuer:" + jwt.getIssuer());
            Log.info("  Subject:" + jwt.getSubject());
            Log.info("  Audience:" + jwt.getAudience());
            Log.info("  Issued At:" + jwt.getIssuedAtTime());
            Log.info("  Expires At:" + jwt.getExpirationTime());
            long epochSeconds = System.currentTimeMillis() / 1000L;

            // Convert seconds since epoch to LocalDateTime
            LocalDateTime localDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(jwt.getExpirationTime()), ZoneId.systemDefault());

            Log.info("Token will expire at: " + localDate);
            // convert localDate to date
            date = Date.from(localDate.atZone(ZoneId.systemDefault()).toInstant());

        }

        return Response.ok("Principle:" +
                securityContext.getUserPrincipal().getName()
                + " is authenticated/n" + "Token will expire at: " + date).build();
    }
}
