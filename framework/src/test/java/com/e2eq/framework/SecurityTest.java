package com.e2eq.framework;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.UserProfile;
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



    @Test
    public void testLoginRepo() throws Exception {

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {

            Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(testUtils.getSystemUserId());

            if (credop.isPresent()) {
                Log.info("cred:" + credop.get().getUserId());
            } else {
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(testUtils.getSystemUserId());
                cred.setPasswordHash("$2a$12$76wQJLgSAdm6ZTHFHtzksuSkWG9eW0qe5YXMXaZIBo52ncXHO0EDy"); //Test123456å

                DataDomain dataDomain = new DataDomain();
                dataDomain.setOrgRefName(testUtils.getTestOrgRefName());
                dataDomain.setAccountNum(testUtils.getTestAccountNumber());
                dataDomain.setTenantId(testUtils.getTestTenantId());
                dataDomain.setOwnerId(testUtils.getTestUserId());

                cred.setRoles(roles);
                cred.setRefName(cred.getUserId());
                cred.setDomainContext(new DomainContext(dataDomain, testUtils.getDefaultRealm()));
                cred.setLastUpdate(new Date());

                cred.setDataDomain(dataDomain);

                credRepo.save(cred);
            }

            Optional<UserProfile> userProfileOp = userProfileRepo.getByUserId(testUtils.getSystemUserId());

            if (userProfileOp.isPresent()) {
                Log.info("User Name:" + userProfileOp.get().getUserName());
            } else {
                UserProfile profile = new UserProfile();

                profile.setRefName(testUtils.getTestUserId());
                profile.setUserId(profile.getRefName());
                profile.setUserName("Michael Ingardia");
                profile.setEmail(testUtils.getTestEmail());

                DataDomain dataDomain = new DataDomain();
                dataDomain.setOrgRefName(testUtils.getTestOrgRefName());
                dataDomain.setAccountNum(testUtils.getTestAccountNumber());
                dataDomain.setTenantId(testUtils.getTestTenantId());
                dataDomain.setOwnerId(profile.getUserId());
                profile.setDataDomain(dataDomain);

                profile = userProfileRepo.save(profile);
                Assert.assertNotNull(profile.getId());
                userProfileRepo.delete(profile);
                userProfileOp = userProfileRepo.getByUserId(testUtils.getSystemUserId());
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
            request.setUserId(securityUtils.getSystemUserId());
            request.setPassword("test123456");
            request.setTenantId(securityUtils.getSystemTenantId());
        } else {
            request.setUserId("testuser@end2endlogic.com");
            request.setPassword("P@55w@rd");
            request.setTenantId(securityUtils.getSystemTenantId());
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
            request.setUserId(securityUtils.getSystemUserId());
            request.setPassword("test123456");
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
