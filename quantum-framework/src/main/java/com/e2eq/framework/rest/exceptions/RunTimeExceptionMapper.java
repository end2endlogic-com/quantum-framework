package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class RunTimeExceptionMapper implements ExceptionMapper<RuntimeException> {

    @Override
    public Response toResponse(RuntimeException exception) {
        if (exception instanceof WebApplicationException webApplicationException) {
            Response originalResponse = webApplicationException.getResponse();
            int status = originalResponse != null
                ? originalResponse.getStatus()
                : Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();

            // JAX-RS application exceptions represent expected HTTP outcomes.
            if (status >= 500) {
                ExceptionLoggingUtils.logError(exception, "Web application exception");
            } else {
                ExceptionLoggingUtils.logDebug(exception, "Web application exception");
            }

            RestError error = RestError.builder().build();
            error.setStatusMessage(exception.getMessage());
            error.setStatus(status);
            error.setReasonMessage(originalResponse != null && originalResponse.getStatusInfo() != null
                ? originalResponse.getStatusInfo().getReasonPhrase()
                : "Web application exception");
            error.setDebugMessage(ExceptionLoggingUtils.getStackTrace(exception));

            return Response.status(status).entity(error).build();
        }

        ExceptionLoggingUtils.logError(exception, "An unexpected / uncaught exception occurred");

        RestError error = RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setReasonMessage("An unexpected / uncaught exception occurred");
        error.setDebugMessage(ExceptionLoggingUtils.getStackTrace(exception));

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}
