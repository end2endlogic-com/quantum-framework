package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.oauth.session.BrowserSignIn;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Ends issuer browser reuse; existing access/refresh token revocation is a separate lifecycle. */
@Path("/oauth/session/logout")
@PermitAll
public class OAuthBrowserSessionResource {
    @Inject BrowserSignIn signIn;
    @Context HttpHeaders headers;
    @Context UriInfo uri;

    @GET @Produces(MediaType.TEXT_HTML)
    public Response form() {
        requireEnabled();
        signIn.requireOrigin(uri);
        String proof = signIn.logoutProof(headers);
        return secure(Response.ok("<form method=\"POST\"><input type=\"hidden\" name=\"csrf\" value=\""
                + proof + "\"><button type=\"submit\">Sign out</button></form>"));
    }
    @POST @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logout(@FormParam("csrf") String csrf) {
        requireEnabled();
        signIn.requireOrigin(uri);
        signIn.logout(headers, csrf);
        return secure(Response.ok("Signed out").header("Set-Cookie", BrowserSignIn.cookieHeader(BrowserSignIn.SESSION_COOKIE, "", 0)));
    }
    private void requireEnabled() { if (!signIn.enabled()) throw new NotFoundException(); }
    private static Response secure(Response.ResponseBuilder response) {
        return response.header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'").build();
    }
}
