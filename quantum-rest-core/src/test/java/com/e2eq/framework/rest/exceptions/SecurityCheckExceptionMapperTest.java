package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SecurityCheckExceptionMapperTest {

    @Test
    void aPolicyDenialIsA403WithAStableMessageThatLeaksNothing() {
        String internal = "Write denied: user=alice may not update UserRealmRole for dataDomain={tenantId=t-9}"
                + " (rule 'tenant admin can administer the tenant records')";
        Response response = new SecurityCheckExceptionMapper().toResponse(new SecurityCheckException(null, internal));

        assertEquals(403, response.getStatus());
        RestError body = assertInstanceOf(RestError.class, response.getEntity());
        assertEquals(403, body.getStatus());
        assertEquals(SecurityCheckExceptionMapper.CODE, body.getStatusMessage());
        assertEquals(SecurityCheckExceptionMapper.REASON, body.getReasonMessage());
        assertFalse(String.valueOf(body.getReasonMessage()).contains("t-9"));
        assertFalse(String.valueOf(body.getDebugMessage()).contains("t-9"));
    }
}
