package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo.TenantDefaultRequiredException;
import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TenantDefaultRequiredExceptionMapperTest {

    @Test
    void aGrantWithoutADefaultTenantIsAConflictWithItsCode() {
        Response response = new TenantDefaultRequiredExceptionMapper().toResponse(
                new TenantDefaultRequiredException("'bob' would reach several tenants in realm 'pooled'"));
        assertEquals(409, response.getStatus());
        RestError body = assertInstanceOf(RestError.class, response.getEntity());
        assertEquals("TENANT_DEFAULT_REQUIRED", body.getStatusMessage());
        assertEquals(409, body.getStatus());
    }
}
