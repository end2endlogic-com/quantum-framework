package com.e2eq.framework.test;

import com.e2eq.framework.model.security.ApplicationRegistration;
import com.e2eq.framework.rest.models.RegistrationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.restassured.response.Response;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

public class TestRegistration {

    //@Test
    public void testRegister() throws JsonProcessingException {
        RegistrationRequest registrationRequest = new  RegistrationRequest();

        registrationRequest.setEmail("tuser@mycompanyxyz.com");
        registrationRequest.setCompanyName("mycompanyxyz.com");
        registrationRequest.setFname("Michael");
        registrationRequest.setLname("Ingardia");
        registrationRequest.setPassword("testPassword");
        registrationRequest.setTelephone("404-555-1212");

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

        if (cr.statusCode() != 200 ) {
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
}
