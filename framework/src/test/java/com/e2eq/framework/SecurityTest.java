package com.e2eq.framework;

import com.e2eq.framework.model.persistent.base.DataDomain;
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

    public void testRegApprovalViaRepo() {

    }

    @Test
    public void testLoginRepo() throws Exception {
        TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
        String[] roles = {"user"};
        PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
        ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {

            Optional<CredentialUserIdPassword> credop = credRepo.findByUserId(TestUtils.userId);

            if (credop.isPresent()) {
                Log.info("cred:" + credop.get().getUserId());
            } else {
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(TestUtils.userId);
                cred.setPasswordHash("$2a$12$76wQJLgSAdm6ZTHFHtzksuSkWG9eW0qe5YXMXaZIBo52ncXHO0EDy"); //Test123456å

                cred.setRoles(roles);
                cred.setRefName(cred.getUserId());
                cred.setDefaultRealm(TestUtils.defaultRealm);
                cred.setLastUpdate(new Date());
                DataDomain dataDomain = new DataDomain();
                dataDomain.setOrgRefName(TestUtils.orgRefName);
                dataDomain.setAccountNum(TestUtils.accountNumber);
                dataDomain.setTenantId(TestUtils.tenantId);
                dataDomain.setOwnerId(TestUtils.userId);
                cred.setDataDomain(dataDomain);

                credRepo.save(cred);
            }

            Optional<UserProfile> userProfileOp = userProfileRepo.getByUserId(TestUtils.userId);

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
                userProfileOp = userProfileRepo.getByUserId(TestUtils.userId);
                Assert.assertTrue(!userProfileOp.isPresent());
            }
        } finally {
            ruleContext.clear();
        }
    }


    @Test
    public void testLoginAPI() throws JsonProcessingException {
        AuthRequest request = new AuthRequest();
        request.setUserId(SecurityUtils.systemUserId);
        request.setPassword("test123456");
        request.setTenantId(SecurityUtils.systemTenantId);

        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(request);
        given()
            .header("Content-type", "application/json")
            .and()
            .body(value)
            .when().post("/security/login")
            .then()
                .statusCode(200);
    }

}
