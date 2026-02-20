package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.auth.provider.jwtToken.CustomTokenAuthProvider;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.rest.requests.CreateUserRequest;
import com.e2eq.framework.rest.requests.ServiceTokenRequest;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.*;

@Path("/auth")
@Tag(name = "Authentication", description = "Operations related to user authentication and management")

public class AuthResource {

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    JsonWebToken jwt;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    EnvConfigUtils envConfigUtils;


    @GET
    @Path("/provider/name")
    @Operation(summary = "Get Authentication Provider Name", description = "Returns the name of the current authentication provider.")
    @APIResponse(responseCode = "200", description = "Successfully retrieved prov")
    @RolesAllowed({ "user","admin", "system"})
    public Response getProviderName() {
        AuthProvider authProvider = authProviderFactory.getAuthProvider();
        return Response.ok(authProvider.getName()).build();
    }

    @POST
    @Path("/create-user")
    @Operation(summary = "Create User", description = "Creates a new user with the provided details with in the auth provider only")
    @APIResponses({
       @APIResponse(responseCode = "200", description = "User created successfully"),
       @APIResponse(responseCode = "400", description = "Invalid request data")
    })
    @RolesAllowed({ "admin", "system"})
    public Response createUser(CreateUserRequest request,
                               @QueryParam("provider") @DefaultValue("") String provider) {
        UserManagement usermanager = provider.isBlank()
                ? authProviderFactory.getUserManager()
                : authProviderFactory.getUserManager(provider);
        usermanager.createUser(request.getUserId(), request.getPassword(),
            request.getRoles(), request.getDomainContext());
        return Response.ok().build();
    }

    @POST
    @Path("/login")
    @Produces("application/json")
    @Operation(summary = "User Login", description = "Authenticates a user and returns access and refresh tokens. this auth is only against the auth provider")
    @APIResponses({
       @APIResponse(responseCode = "200", description = "Login successful"),
       @APIResponse(responseCode = "401", description = "Unauthorized")
    })
    @PermitAll
    public Response login(@QueryParam("userId") String userId,
                          @QueryParam("password") String password,
                          @QueryParam("provider") @DefaultValue("") String provider) {
        var authProvider = provider.isBlank()
                ? authProviderFactory.getAuthProvider()
                : authProviderFactory.getProviderByName(provider);
        var loginResponse = authProvider.login(userId, password);

        if (!loginResponse.authenticated() || loginResponse.positiveResponse() == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Authentication failed")).build();
        }

        return Response.ok(Map.of(
            "accessToken", loginResponse.positiveResponse().accessToken(),
            "refreshToken", loginResponse.positiveResponse().refreshToken(),
            "authProvider", authProvider.getName()
        )).build();
    }

    @POST
    @Path("/refresh")
    @Operation(summary = "Logins with a refresh token", description = "Authenticates a user and returns access and refresh tokens. this auth is only against the auth provider")
    @APIResponses({
       @APIResponse(responseCode = "200", description = "Login successful"),
       @APIResponse(responseCode = "401", description = "Unauthorized")
    })
    @PermitAll
    public Response refresh(@HeaderParam("Authorization") String refreshToken,
                            @QueryParam("provider") @DefaultValue("") String provider) {
        var authProvider = provider.isBlank()
                ? authProviderFactory.getAuthProvider()
                : authProviderFactory.getProviderByName(provider);
        if (refreshToken == null || refreshToken.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        try {
            // Remove "Bearer " if present
            String token = refreshToken.replace("Bearer ", "");
            AuthProvider.LoginResponse response = authProvider.refreshTokens(token);
            return Response.ok(response).build();
        } catch (Exception e) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
    }

    @POST
    @Path("/service-token")
    @Produces("application/json")
    @Operation(summary = "Generate Service Token",
            description = "Generates a long-lived JWT token for service accounts, MCP servers, and API integrations. " +
                    "Creates a SERVICE_TOKEN credential linked to the caller's user profile. " +
                    "Requires the 'custom' auth provider to be configured.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Service token generated and credential persisted"),
            @APIResponse(responseCode = "400", description = "Custom JWT provider not available or caller credential not found"),
            @APIResponse(responseCode = "500", description = "Internal error during credential creation")
    })
    @RolesAllowed({"admin", "system"})
    public Response generateServiceToken(ServiceTokenRequest request) {
        try {
            // 1. Resolve the custom JWT provider
            AuthProvider provider = authProviderFactory.getProviderByName("custom");
            if (!(provider instanceof CustomTokenAuthProvider ctp)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Custom JWT provider not available")).build();
            }

            // 2. Get the caller's subject from the JWT and look up their credential
            String callerSubject = jwt.getSubject();
            if (callerSubject == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Caller subject not found in JWT")).build();
            }

            String systemRealm = envConfigUtils.getSystemRealm();
            Optional<CredentialUserIdPassword> callerCredOpt =
                    credentialRepo.findBySubject(callerSubject, systemRealm, true);
            if (callerCredOpt.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error",
                                "Caller credential not found for subject: " + callerSubject)).build();
            }
            CredentialUserIdPassword callerCred = callerCredOpt.get();

            // 3. Generate a new subject (UUID) for the service token
            String serviceSubject = UUID.randomUUID().toString();
            String serviceUserId = "svc:" + serviceSubject;

            // 4. Create the SERVICE_TOKEN credential
            CredentialUserIdPassword serviceCred = CredentialUserIdPassword.builder()
                    .userId(serviceUserId)
                    .subject(serviceSubject)
                    .credentialType(CredentialType.SERVICE_TOKEN)
                    .parentCredentialSubject(callerSubject)
                    .roles(request.roles() != null ? request.roles().toArray(new String[0]) : new String[0])
                    .issuer(ctp.getIssuer())
                    .authProviderName("custom")
                    .domainContext(callerCred.getDomainContext())
                    .description(request.description())
                    .lastUpdate(new Date())
                    .refName(serviceUserId)
                    .displayName(request.description() != null ? request.description() : serviceUserId)
                    .build();

            // 5. Save the credential
            serviceCred = credentialRepo.save(serviceCred);
            Log.infof("Created SERVICE_TOKEN credential: userId=%s, subject=%s, parent=%s",
                    serviceUserId, serviceSubject, callerSubject);

            // 6. Link to the caller's UserProfile via additionalCredentialRefs
            EntityReference serviceCredRef = serviceCred.createEntityReference(systemRealm);
            Optional<UserProfile> profileOpt = userProfileRepo.getBySubject(callerSubject);
            if (profileOpt.isPresent()) {
                UserProfile profile = profileOpt.get();
                if (profile.getAdditionalCredentialRefs() == null) {
                    profile.setAdditionalCredentialRefs(new ArrayList<>());
                }
                profile.getAdditionalCredentialRefs().add(serviceCredRef);
                userProfileRepo.save(profile);
                Log.infof("Linked SERVICE_TOKEN credential to UserProfile for subject=%s", callerSubject);
            } else {
                Log.warnf("No UserProfile found for subject=%s; credential created but not linked to a profile", callerSubject);
            }

            // 7. Generate the JWT
            String token = ctp.generateServiceToken(
                    serviceSubject, request.roles(), request.expirationSeconds());

            // 8. Return the token and metadata
            return Response.ok(Map.of(
                    "accessToken", token,
                    "issuer", ctp.getIssuer(),
                    "subject", serviceSubject,
                    "credentialType", CredentialType.SERVICE_TOKEN.name()
            )).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error",
                            "Custom JWT provider not configured. Add 'custom' to auth.provider config.")).build();
        } catch (Exception e) {
            Log.errorf(e, "Failed to generate service token");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to generate service token: " + e.getMessage())).build();
        }
    }
}
