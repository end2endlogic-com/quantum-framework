package com.e2eq.framework.rest.filters;

import com.e2eq.framework.exceptions.DatabaseMigrationException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The typed 409 {@code REALM_NOT_MIGRATED} refusal: the realm the request runs in has no current
 * database version in this service. The body names the realm and says what to do; what the
 * migration check found is logged, never returned. Kept apart from the request filters so the
 * response body is built where no token is read.
 */
final class RealmNotMigratedProblem {

    static final String REALM_NOT_MIGRATED = "REALM_NOT_MIGRATED";

    private RealmNotMigratedProblem() {
    }

    static WebApplicationException of(String realm, DatabaseMigrationException cause) {
        Log.warnf("Realm %s is not migrated in this service: %s", realm, cause == null ? null : cause.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", REALM_NOT_MIGRATED);
        body.put("message", "Realm " + realm + " is not migrated in this service; run its migrations, or "
                + "make the request in a realm this service serves");
        body.put("realm", realm);
        Response response = Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store")
                .entity(body)
                .build();
        return new WebApplicationException("Realm " + realm + " is not migrated in this service", cause, response);
    }
}
