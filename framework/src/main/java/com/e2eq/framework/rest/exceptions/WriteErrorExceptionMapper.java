package com.e2eq.framework.rest.exceptions;
import com.e2eq.framework.rest.models.RestError;
import com.mongodb.DuplicateKeyException;
import com.mongodb.MongoWriteException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.PrintWriter;
import java.io.StringWriter;

@Provider
public class WriteErrorExceptionMapper implements ExceptionMapper<MongoWriteException> {
    @Override
    public Response toResponse(MongoWriteException exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();
        RestError error;
        if (exception.getCode() == 11000) {
            error = RestError.builder()
                    .statusMessage("The request failed due to a duplicate key violation.  The API call failed because of a unique index and the provided data violates this uniqueness.")
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .reasonMessage(exception.getMessage())
                    .debugMessage(stackTrace)
                    .reasonCode(exception.getCode())
                    .build();
        }
         else {
             error = RestError.builder()
                    .statusMessage("The request failed due to a write error. Check the database configuration and server side database logs for more details")
                    .status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                    .reasonMessage(exception.getMessage())
                    .debugMessage(stackTrace)
                    .reasonCode(exception.getCode())
                    .build();
            }

        return Response.status(error.getStatus()).entity(error).build();
    }
}
