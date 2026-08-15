package com.e2eq.framework.rest.ratelimit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/__quantum-rate-limit-test")
public class RateLimitTestResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String get() {
        return "ok";
    }
}
