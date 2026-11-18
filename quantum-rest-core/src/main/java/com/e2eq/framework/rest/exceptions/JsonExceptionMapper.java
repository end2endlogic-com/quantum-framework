package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class JsonExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
        // Use proper logging instead of printStackTrace
        ExceptionLoggingUtils.logError(exception, "An unexpected / uncaught exception occurred");
        
        RestError error = RestError.builder().build();
        // A checked exception's message is a library's or a driver's (hosts, addresses, databases):
        // logged above, returned only with debug detail.
        error.setStatusMessage(ClientErrorDetail.includeDebugDetail()
                ? exception.getMessage() : ClientErrorDetail.UNEXPECTED_ERROR_MESSAGE);
        error.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setReasonMessage("An unexpected / uncaught exception occurred");
        error.setDebugMessage(ClientErrorDetail.stackTrace(exception));

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}
