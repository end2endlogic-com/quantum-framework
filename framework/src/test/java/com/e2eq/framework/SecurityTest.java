package com.e2eq.framework;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.security.auth.AuthProviderFactory;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.rest.models.AuthRequest;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.smallrye.common.constraint.Assert;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.UUID;

import static io.restassured.RestAssured.given;


@QuarkusTest
public class SecurityTest extends BaseRepoTest {

    @ConfigProperty(name = "auth.provider", defaultValue = "custom")
    String authProvider;



    @Inject
    CredentialRepo credRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    SecurityUtils securityUtils;
   @Inject
   AuthProviderFactory authProviderFactory;


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


    public UserProfile ensureTestUserExists() throws ReferentialIntegrityViolationException {
        Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(testUtils.getTestUserId(), testUtils.getTestRealm());
        CredentialUserIdPassword cred;
        if (credop.isPresent()) {
            Log.info("cred:" + credop.get().getUserId());
            cred = credop.get();
        } else {
            cred = new CredentialUserIdPassword();
            cred.setUserId(testUtils.getTestUserId());
          //  cred.setPasswordHash("$2a$12$76wQJLgSAdm6ZTHFHtzksuSkWG9eW0qe5YXMXaZIBo52ncXHO0EDy"); //Test123456
           cred.setPasswordHash(EncryptionUtils.hashPassword(testUtils.getDefaultTestPassword()));
            cred.setUsername(UUID.randomUUID().toString());

            DataDomain dataDomain = new DataDomain();
            dataDomain.setOrgRefName(testUtils.getTestOrgRefName());
            dataDomain.setAccountNum(testUtils.getTestAccountNumber());
            dataDomain.setTenantId(testUtils.getTestTenantId());
            dataDomain.setOwnerId(testUtils.getTestUserId());

            cred.setRoles(roles);
            cred.setRefName(cred.getUserId());
            cred.setDomainContext(new DomainContext(dataDomain, testUtils.getTestRealm()));
            cred.setLastUpdate(new Date());
            cred.setDataDomain(dataDomain);
            cred = credRepo.save(testUtils.getTestRealm(),cred);
        }

        Optional<UserProfile> userProfileOp = userProfileRepo.getByUserId(testUtils.getTestRealm(),testUtils.getTestUserId());
        UserProfile profile;

        if (userProfileOp.isPresent()) {
            Log.info("User Id:" + userProfileOp.get().getUserId());
            profile = userProfileOp.get();

        } else {
            profile = new UserProfile();
            profile.setRefName(testUtils.getTestUserId());
            profile.setUserId(testUtils.getTestUserId());
            profile.setUsername(testUtils.getTestUserId());
            profile.setEmail(testUtils.getTestEmail());

            DataDomain dataDomain = new DataDomain();
            dataDomain.setOrgRefName(testUtils.getTestOrgRefName());
            dataDomain.setAccountNum(testUtils.getTestAccountNumber());
            dataDomain.setTenantId(testUtils.getTestTenantId());
            dataDomain.setOwnerId(testUtils.getTestUserId());
            profile.setDataDomain(dataDomain);

            profile = userProfileRepo.save(testUtils.getTestRealm(),profile);
        }
        return profile;
    }


    @Test
    public void testLoginRepo() throws Exception {

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
           UserProfile profile = ensureTestUserExists();
            Assert.assertNotNull(profile.getId());
            Assert.assertTrue(credRepo.findByUserId(testUtils.getTestUserId(), testUtils.getTestRealm()).isPresent());

            authProviderFactory.getAuthProvider().login(testUtils.getTestRealm(), profile.getUserId(), testUtils.getDefaultTestPassword());
            Assert.assertTrue(userProfileRepo.delete(testUtils.getTestRealm(), profile)==1);
            Optional<UserProfile> userProfileOp = userProfileRepo.getByUserId(testUtils.getTestRealm(),testUtils.getTestUserId());
            Assert.assertFalse(userProfileOp.isPresent());
            Assert.assertFalse(credRepo.findByUserId(testUtils.getTestUserId(), testUtils.getTestRealm()).isPresent());
        } finally {
            ruleContext.clear();
        }
    }


    @Test
    public void testLoginAPI() throws JsonProcessingException, ReferentialIntegrityViolationException {
        ensureTestUserExists();
        AuthRequest request = new AuthRequest();
        if (authProvider.equals("custom")) {
            request.setUserId(securityUtils.getTestUserId());
            request.setPassword(testUtils.getDefaultTestPassword());
           // request.setTenantId(securityUtils.getSystemTenantId());
        } else {
            request.setUserId("testuser@end2endlogic.com");
            request.setPassword("P@55w@rd");
           // request.setTenantId(securityUtils.getSystemTenantId());
        }

        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(request);
        given()
            .header("Content-type", "application/json")
           .header("X-Realm", testUtils.getTestRealm())
            .and()
            .body(value)
            .when().post("/security/login")
            .then()
            .statusCode(200);

        request.setPassword("incorrect");
        value = mapper.writeValueAsString(request);
        given()
                .header("Content-type", "application/json")
                .header("X-Realm", testUtils.getTestRealm())
                .and()
                .body(value)
                .when().post("/security/login")
                .then()
                .statusCode(jakarta.ws.rs.core.Response.Status.UNAUTHORIZED.getStatusCode());
    }

    @Test
    public void testGetUserProfileRESTAPI() throws JsonProcessingException, ReferentialIntegrityViolationException {

        // ensure that the user exists;
        ensureTestUserExists();


        AuthRequest request = new AuthRequest();
        if (authProvider.equals("custom")) {
            request.setUserId(testUtils.getTestUserId());
            request.setPassword(testUtils.getDefaultTestPassword());
            //request.setTenantId(securityUtils.getSystemTenantId());
        } else {
            request.setUserId("testuser@end2endlogic.com");
            request.setPassword("P@55w@rd");
           // request.setTenantId(securityUtils.getSystemTenantId());
        }

        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(request);
        // ensure that the status code is 200 but also get the access token and refresh token from the response
        Response response = given()
                .header("Content-type", "application/json")
                .header("X-Realm", testUtils.getTestRealm())
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
                .header("X-Realm", testUtils.getTestRealm())
                .when().get("/user/userProfile/list")
                .then()
                .statusCode(200)
                .extract().response();
        String userProfileJson = response2.jsonPath().prettify();
        Log.info(userProfileJson);


    }

}
