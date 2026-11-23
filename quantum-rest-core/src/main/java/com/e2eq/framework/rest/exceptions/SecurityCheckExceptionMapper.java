package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.rest.models.RestError;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A policy denial reaching a REST boundary (a rule refusing an action, or a write filter refusing
 * a record outside the caller's data domain) is a 403, not an unexpected 500.
 *
 * <p>The answer carries a stable code and message only. The exception's own message can name the
 * record's data domain, the rule and underlying resolver or predicate failures; it is logged, never
 * returned.</p>
 */
@Provider
public class SecurityCheckExceptionMapper implements ExceptionMapper<SecurityCheckException> {

    /** The status message of every policy denial. */
    public static final String CODE = "SECURITY_POLICY_DENIED";

    /** The reason message of every policy denial. */
    public static final String REASON = "The caller's security policy does not allow this action on this record.";

    @Override
    public Response toResponse(SecurityCheckException exception) {
        Log.warnf("Policy denied the request: %s", exception.getMessage());
        RestError error = RestError.builder()
                .status(Response.Status.FORBIDDEN.getStatusCode())
                .statusMessage(CODE)
                .reasonMessage(REASON)
                .build();
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON).entity(error).build();
    }
}
