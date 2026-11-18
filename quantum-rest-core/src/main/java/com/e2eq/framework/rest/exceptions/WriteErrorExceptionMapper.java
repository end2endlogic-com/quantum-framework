package com.e2eq.framework.rest.exceptions;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import com.mongodb.MongoWriteException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class WriteErrorExceptionMapper implements ExceptionMapper<MongoWriteException> {

    /** The reasonMessage of a duplicate key violation when debug detail is off. */
    static final String DUPLICATE_KEY_REASON = "Conflict: a record with the same unique value already exists";

    /** The reasonMessage of any other write error when debug detail is off. */
    static final String WRITE_ERROR_REASON = "The write was not accepted";

    @Override
    public Response toResponse(MongoWriteException exception) {
        String stackTrace = ClientErrorDetail.stackTrace(exception);
        
        // Log at appropriate level based on error code
        if (exception.getCode() == 11000) {
            ExceptionLoggingUtils.logWarn(exception, "Duplicate key violation (code 11000)");
        } else {
            ExceptionLoggingUtils.logError(exception, "MongoDB write error (code %d)", exception.getCode());
        }
        
        // The driver's message names the host, database, collection, index and the duplicate key's
        // value (an existing account's userId, say): logged above, returned only with debug detail.
        boolean debug = ClientErrorDetail.includeDebugDetail();
        RestError error;
        if (exception.getCode() == 11000) {
            error = RestError.builder()
                    .statusMessage("The request failed due to a duplicate key violation.  The API call failed because of a unique index and the provided data violates this uniqueness.")
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .reasonMessage(debug ? exception.getMessage() : DUPLICATE_KEY_REASON)
                    .debugMessage(stackTrace)
                    .reasonCode(exception.getCode())
                    .build();
        } else {
            error = RestError.builder()
                    .statusMessage("The request failed due to a write error. Check the database configuration and server side database logs for more details")
                    .status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
                    .reasonMessage(debug ? exception.getMessage() : WRITE_ERROR_REASON)
                    .debugMessage(stackTrace)
                    .reasonCode(exception.getCode())
                    .build();
        }

        return Response.status(error.getStatus()).entity(error).build();
    }
}
