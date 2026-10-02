package com.e2eq.framework.cli;
import com.e2eq.framework.cli.generated.api.AuthEndpoint;
import com.e2eq.framework.cli.generated.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServiceTokenCLITest {
 @Test void currentTokenResponseSucceedsAndMissingTokenFails() throws Exception {
  assertEquals("/security/login",AuthEndpoint.class.getMethod("login",LoginRequest.class).getAnnotation(jakarta.ws.rs.Path.class).value());
  assertEquals("/security/service-token",AuthEndpoint.class.getMethod("mintServiceToken",String.class,ServiceTokenRequest.class).getAnnotation(jakarta.ws.rs.Path.class).value());
  var response=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"token\":\"synthetic-token\",\"subject\":\"subject\"}",ServiceTokenResponse.class);
  var cli=new ServiceTokenCLI(){
   @Override AuthEndpoint createClient(){return new AuthEndpoint(){
    public LoginResponse login(LoginRequest request){var result=new LoginResponse(); result.setAccessToken("login"); return result;}
    public ServiceTokenResponse mintServiceToken(String authorization,ServiceTokenRequest request){assertEquals("Bearer login",authorization); return response;}
   };}
  };
  assertEquals(0,cli.call()); response.setToken(null); assertEquals(1,cli.call());
 }
}
