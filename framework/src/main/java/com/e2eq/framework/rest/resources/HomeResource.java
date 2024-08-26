package com.e2eq.framework.rest.resources;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.jwt.JsonWebToken;


@Path("/tenant/{tenant}")
@RolesAllowed({ "user", "admin" })
public class HomeResource {

   /**
    * Injection point for the ID Token issued by the OpenID Connect Provider
    */
   @Inject
   JsonWebToken idToken;

   /**
    * Returns the tokens available to the application. This endpoint exists only for demonstration purposes, you should not
    * expose these tokens in a real application.
    *
    * @return the landing page HTML
    */
   @GET
   @Authenticated
   public String getHome() {
      StringBuilder response = new StringBuilder().append("<html>").append("<body>");

      response.append("<h2>Welcome, ").append(this.idToken.getClaim("email").toString()).append("</h2>\n");
      response.append("<h3>You are accessing the application within tenant <b>").append(idToken.getIssuer()).append(" boundaries</b></h3>");

      return response.append("</body>").append("</html>").toString();
   }
}
