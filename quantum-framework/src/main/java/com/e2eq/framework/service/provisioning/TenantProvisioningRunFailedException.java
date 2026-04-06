package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.model.security.TenantProvisioningRun;
import jakarta.ws.rs.core.Response;

public class TenantProvisioningRunFailedException extends RuntimeException {
    private final transient TenantProvisioningRun run;
    private final Response.Status responseStatus;

    public TenantProvisioningRunFailedException(
        TenantProvisioningRun run,
        Response.Status responseStatus,
        String message,
        Throwable cause
    ) {
        super(message, cause);
        this.run = run;
        this.responseStatus = responseStatus;
    }

    public TenantProvisioningRun getRun() {
        return run;
    }

    public Response.Status getResponseStatus() {
        return responseStatus;
    }
}
