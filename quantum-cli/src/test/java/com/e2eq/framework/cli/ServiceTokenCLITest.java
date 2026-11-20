package com.e2eq.framework.cli;

import com.e2eq.framework.cli.generated.api.AuthEndpoint;
import com.e2eq.framework.cli.generated.model.LoginRequest;
import com.e2eq.framework.cli.generated.model.LoginResponse;
import com.e2eq.framework.cli.generated.model.ServiceTokenRequest;
import com.e2eq.framework.cli.generated.model.ServiceTokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ServiceTokenCLITest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void issuesAnIdentityOnlyTokenAndFailsWithoutOne() throws Exception {
        assertEquals("/security/login", AuthEndpoint.class.getMethod("login", LoginRequest.class)
                .getAnnotation(jakarta.ws.rs.Path.class).value());
        assertEquals("/security/service-token", AuthEndpoint.class.getMethod("mintServiceToken",
                String.class, String.class, ServiceTokenRequest.class).getAnnotation(jakarta.ws.rs.Path.class).value());
        ServiceTokenResponse response = JSON.readValue("{\"token\":\"synthetic-token\",\"subject\":\"s\","
                + "\"userId\":\"svc-s\",\"kind\":\"delegating\",\"realm\":\"r\"}", ServiceTokenResponse.class);
        AtomicReference<String> sentRealm = new AtomicReference<>();
        AtomicReference<String> sentBody = new AtomicReference<>();
        ServiceTokenCLI cli = cli(response, sentRealm, sentBody);
        cli.userId = "admin";
        cli.password = "pw";
        cli.kind = "delegating";
        cli.realm = "r";
        cli.description = "worker";

        assertEquals(0, cli.call());
        assertEquals("r", sentRealm.get());
        assertEquals(JSON.readTree("{\"kind\":\"delegating\",\"description\":\"worker\"}"), JSON.readTree(sentBody.get()));

        response.setToken(null);
        assertEquals(1, cli.call());
    }

    @Test
    void defaultsToAnApplicationTokenAndSendsNothingElse() throws Exception {
        ServiceTokenResponse response = JSON.readValue("{\"token\":\"t\",\"subject\":\"s\",\"userId\":\"svc-s\","
                + "\"kind\":\"application\",\"realm\":\"r\"}", ServiceTokenResponse.class);
        AtomicReference<String> sentBody = new AtomicReference<>();
        ServiceTokenCLI cli = cli(response, new AtomicReference<>(), sentBody);
        assertEquals("application", new CommandLine(new ServiceTokenCLI()).getCommandSpec()
                .findOption("--kind").defaultValue());
        cli.userId = "admin";
        cli.password = "pw";
        cli.kind = "application";

        assertEquals(0, cli.call());
        assertEquals(JSON.readTree("{\"kind\":\"application\"}"), JSON.readTree(sentBody.get()));
    }

    @Test
    void theRetiredRoleAndExpiryOptionsAreGone() {
        for (String retired : new String[]{"--roles", "--expires", "--audience"}) {
            CommandLine command = new CommandLine(new ServiceTokenCLI());
            assertFalse(command.getCommandSpec().optionsMap().containsKey(retired), retired);
        }
    }

    private static ServiceTokenCLI cli(ServiceTokenResponse response, AtomicReference<String> sentRealm,
                                       AtomicReference<String> sentBody) {
        return new ServiceTokenCLI() {
            @Override
            AuthEndpoint createClient() {
                return new AuthEndpoint() {
                    public LoginResponse login(LoginRequest request) {
                        LoginResponse result = new LoginResponse();
                        result.setAccessToken("login");
                        return result;
                    }

                    public ServiceTokenResponse mintServiceToken(String authorization, String xRealm,
                                                                 ServiceTokenRequest request) {
                        assertEquals("Bearer login", authorization);
                        sentRealm.set(xRealm);
                        try {
                            sentBody.set(JSON.writeValueAsString(request));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        return response;
                    }
                };
            }
        };
    }
}
