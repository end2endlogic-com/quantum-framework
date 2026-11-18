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

            // A 4xx is an expected outcome whose message the resource wrote for the client; a 5xx
            // message is a failure's own (a wrapped cause's), returned only with debug detail on.
            RestError error = RestError.builder().build();
            error.setStatusMessage(status >= 500 ? ClientErrorDetail.serverErrorMessage(exception) : exception.getMessage());
            error.setStatus(status);
            error.setReasonMessage(originalResponse != null && originalResponse.getStatusInfo() != null
                ? originalResponse.getStatusInfo().getReasonPhrase()
                : "Web application exception");
            error.setDebugMessage(ClientErrorDetail.stackTrace(exception));

            return Response.status(status).entity(error).build();
        }

        ExceptionLoggingUtils.logError(exception, "An unexpected / uncaught exception occurred");

        // An unmapped exception's message is a driver's or a library's (a MongoDB command error
        // names hosts and databases): it is logged above, never returned unless debug detail is on.
        RestError error = RestError.builder().build();
        error.setStatusMessage(ClientErrorDetail.serverErrorMessage(exception));
        error.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setReasonMessage("An unexpected / uncaught exception occurred");
        error.setDebugMessage(ClientErrorDetail.stackTrace(exception));

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}
