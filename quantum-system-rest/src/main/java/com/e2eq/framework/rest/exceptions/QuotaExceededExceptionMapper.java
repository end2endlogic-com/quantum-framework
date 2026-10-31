package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.metering.UsageMeteringService;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link UsageMeteringService.QuotaExceededException} to HTTP 402 Payment Required.
 * When token metering is enabled and enforce-quota is true, associated APIs/tools are blocked
 * when tokens run out; this mapper returns a clear error message including next replenishment date when available.
 */
@Provider
public class QuotaExceededExceptionMapper implements ExceptionMapper<UsageMeteringService.QuotaExceededException> {

    @Override
    public Response toResponse(UsageMeteringService.QuotaExceededException exception) {
        ExceptionLoggingUtils.logDebug(exception, "Quota exceeded");

        RestError error = RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(402);
        error.setReasonMessage(exception.getMessage());
        error.setDebugMessage(ExceptionLoggingUtils.getStackTrace(exception));

        return Response.status(402).entity(error).build();
    }
}
