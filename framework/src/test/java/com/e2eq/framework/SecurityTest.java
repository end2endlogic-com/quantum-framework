package com.e2eq.framework;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.rest.models.RegistrationRequest;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.quarkus.logging.Log;
import io.restassured.response.Response;
import io.smallrye.common.constraint.Assert;
import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;

import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.util.EncryptionUtils;

import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.inject.Inject;
import java.util.Date;
import java.util.Optional;
import java.util.StringTokenizer;


@QuarkusTest
public class SecurityTest {

    @ConfigProperty(name = "auth.provider")
    String authProvider;

    @Inject
    CredentialRepo credRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    RuleContext ruleContext;

    @Test
    public void testPasswordEncryption() {
        String password = "test123456";
        String encrypted = EncryptionUtils.hashPassword(password);
        Assert.assertTrue(EncryptionUtils.checkPassword(password, encrypted));
    }

    @Test
    public void testEmailParse() {
        String email = "mingardia@end2endlogic.com";

        StringTokenizer tokenizer = new StringTokenizer(email, "@");
        String user = tokenizer.nextToken();
        String company = tokenizer.nextToken();

        Log.debug(company);
        Assert.assertTrue(company.equals("end2endlogic.com"));
        Assert.assertTrue(user.equals("mingardia"));

    }



    //@Test
    public void testRegister() throws JsonProcessingException {
        RegistrationRequest registrationRequest = new  RegistrationRequest();

        registrationRequest.setEmail("tuser@test-b2bintegrator.com");
        registrationRequest.setCompanyName("test-b2bintegrator.com");
        registrationRequest.setFname("Michael");
        registrationRequest.setLname("Ingardia");
        registrationRequest.setPassword("testPassword");
        registrationRequest.setTelephone("123-456-7890");
        //registrationRequest.setTenantId("test-b2bintegrator.com");

        ObjectMapper mapper = new ObjectMapper()
           .registerModule(new ParameterNamesModule())
           .registerModule(new Jdk8Module())
           .registerModule(new JavaTimeModule());

        String value = mapper.writeValueAsString(registrationRequest);

        Response cr = given()
            .header("Content-type", "application/json")
            .and()
            .body(value)
            .when().post("/security/register")
            .then()
            .extract().response();
            //.statusCode(201).extract().response();

        if (cr.statusCode() != 20 ) {
            if (cr.statusCode() != 400)
                fail("Unexpected status code:" + cr.statusCode());
        }

    }

    //@Test
    public void testRegistrationApproval() {
        // will need to login for this

        Response gr = given()
           .header("Content-type", "application/json")
           .and()
           .param("refName","tuserId@test-b2bintegrator.com")
           .when().get( "/security/register")
           .then()
           .statusCode(200).extract().response();

        given()
           .header("Content-type", "application/json")
           .and()
           .body(gr.as(ApplicationRegistration.class))
           .when().post( "/onboarding/registrationRequest/approve")
           .then()
           .statusCode(200);
    }


    @Test
    public void testLoginRepo() throws Exception {
        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.systemUserId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {

            Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(TestUtils.systemUserId);

            if (credop.isPresent()) {
                Log.info("cred:" + credop.get().getUserId());
            } else {
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(TestUtils.systemUserId);
                cred.setPasswordHash("$2a$12$76wQJLgSAdm6ZTHFHtzksuSkWG9eW0qe5YXMXaZIBo52ncXHO0EDy"); //Test123456å

                DataDomain dataDomain = new DataDomain();
                dataDomain.setOrgRefName(TestUtils.orgRefName);
                dataDomain.setAccountNum(TestUtils.accountNumber);
                dataDomain.setTenantId(TestUtils.tenantId);
                dataDomain.setOwnerId(TestUtils.systemUserId);

                cred.setRoles(roles);
                cred.setRefName(cred.getUserId());
                cred.setDomainContext(new DomainContext(dataDomain, TestUtils.defaultRealm));
                cred.setLastUpdate(new Date());

                cred.setDataDomain(dataDomain);

                credRepo.save(cred);
            }

            Optional<UserProfile> userProfileOp = userProfileRepo.getByUserId(TestUtils.systemUserId);

            if (userProfileOp.isPresent()) {
                Log.info("User Name:" + userProfileOp.get().getUserName());
            } else {
                UserProfile profile = new UserProfile();

                profile.setRefName("mingardia@end2endlogic.com");
                profile.setUserId(profile.getRefName());
                profile.setUserName("Michael Ingardia");
                profile.setEmail("mingardia@end2endlogic.com");

                DataDomain dataDomain = new DataDomain();
                dataDomain.setOrgRefName(TestUtils.orgRefName);
                dataDomain.setAccountNum(TestUtils.accountNumber);
                dataDomain.setTenantId(TestUtils.tenantId);
                dataDomain.setOwnerId(profile.getUserId());
                profile.setDataDomain(dataDomain);

                profile = userProfileRepo.save(profile);
                Assert.assertNotNull(profile.getId());
                userProfileRepo.delete(profile);
                userProfileOp = userProfileRepo.getByUserId(TestUtils.systemUserId);
                Assert.assertTrue(!userProfileOp.isPresent());
            }
        } finally {
            ruleContext.clear();
        }
    }


    @Test
    public void testLoginAPI() throws JsonProcessingException {
        AuthRequest request = new AuthRequest();
        if (authProvider.equals("custom")) {
            request.setUserId(SecurityUtils.systemUserId);
            request.setPassword("test123456");
            request.setTenantId(SecurityUtils.systemTenantId);
        } else {
            request.setUserId("testuser@end2endlogic.com");
            request.setPassword("P@55w@rd");
            request.setTenantId(SecurityUtils.systemTenantId);
        }

        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(request);
        given()
            .header("Content-type", "application/json")
            .and()
            .body(value)
            .when().post("/security/login")
            .then()
            .statusCode(200);

        request.setPassword("incorrect");
        value = mapper.writeValueAsString(request);
        given()
                .header("Content-type", "application/json")
                .and()
                .body(value)
                .when().post("/security/login")
                .then()
                .statusCode(jakarta.ws.rs.core.Response.Status.UNAUTHORIZED.getStatusCode());
    }

    @Test
    public void testGetUserProfileRESTAPI() throws JsonProcessingException {
        AuthRequest request = new AuthRequest();
        if (authProvider.equals("custom")) {
            request.setUserId(SecurityUtils.systemUserId);
            request.setPassword("test123456");
            //request.setTenantId(SecurityUtils.systemTenantId);
        } else {
            request.setUserId("testuser@end2endlogic.com");
            request.setPassword("P@55w@rd");
           // request.setTenantId(SecurityUtils.systemTenantId);
        }

        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(request);
        // ensure that the status code is 200 but also get the access token and refresh token from the response
        Response response = given()
                .header("Content-type", "application/json")
                .and()
                .body(value)
                .when().post("/security/login")
                .then()
                .statusCode(200)
                .extract().response();

        String accessToken = response.jsonPath().getString("access_token");
        String refreshToken = response.jsonPath().getString("refresh_token");

        Assert.assertNotNull(accessToken);
        Assert.assertNotNull(refreshToken);

        // make a GET request to the user profile API /userProfile/list
        Response response2 = given()
                .header("Content-type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .when().get("/user/userProfile/list")
                .then()
                .statusCode(200)
                .extract().response();
        String userProfileJson = response2.jsonPath().prettify();
        Log.info(userProfileJson);


    }

}
