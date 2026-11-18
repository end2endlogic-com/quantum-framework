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

    @Test
    void aServerErrorWebApplicationExceptionNamesNoInternals() {
        // Round-4 review: a 5xx WebApplicationException returned its message as is.
        for (jakarta.ws.rs.WebApplicationException failed : new jakarta.ws.rs.WebApplicationException[] {
                new jakarta.ws.rs.InternalServerErrorException("Timed out connecting to mongo-prod.internal:27017"),
                new jakarta.ws.rs.ServiceUnavailableException("billing at 10.0.0.9:8080 refused db=quantum-billing")}) {
            Response response = new RunTimeExceptionMapper().toResponse(failed);
            RestError error = (RestError) response.getEntity();
            assertEquals(failed.getResponse().getStatus(), response.getStatus());
            assertEquals(ClientErrorDetail.UNEXPECTED_ERROR_MESSAGE, error.getStatusMessage());
            assertNull(error.getDebugMessage());
        }
    }
}
