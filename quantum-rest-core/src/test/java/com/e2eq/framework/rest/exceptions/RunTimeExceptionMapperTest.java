package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Error responses carry no stack trace and no driver message unless debug detail is enabled. */
class RunTimeExceptionMapperTest {

    @Test
    void anUnexpectedErrorNamesNoInternals() {
        Response response = new RunTimeExceptionMapper().toResponse(
                new IllegalStateException("Command failed on MongoDB server with error 13297 on localhost:27017"));
        RestError error = (RestError) response.getEntity();
        assertEquals(500, response.getStatus());
        assertEquals(ClientErrorDetail.UNEXPECTED_ERROR_MESSAGE, error.getStatusMessage());
        assertNull(error.getDebugMessage());
    }

    @Test
    void aWebApplicationExceptionKeepsItsMessageButNoStackTrace() {
        Response response = new RunTimeExceptionMapper().toResponse(new ForbiddenException("not yours"));
        RestError error = (RestError) response.getEntity();
        assertEquals(403, response.getStatus());
        assertEquals("not yours", error.getStatusMessage());
        assertNull(error.getDebugMessage());
    }
}
