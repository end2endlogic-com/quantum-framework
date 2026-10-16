package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.service.access.AccessInviteException;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessInviteExceptionMapperTest {

    private final AccessInviteExceptionMapper mapper = new AccessInviteExceptionMapper();

    @Test
    void mapsEachCodeToATypedRestError() {
        assertMapped(Code.INVALID_REQUEST, 400);
        assertMapped(Code.INVITE_TOKEN_INVALID, 400);
        assertMapped(Code.INVITE_SINGLE_REALM_REQUIRED, 400);
        assertMapped(Code.INVITE_SIGN_IN_REQUIRED, 401);
        assertMapped(Code.INVITE_IDENTITY_MISMATCH, 403);
        assertMapped(Code.INVITE_NOT_AUTHORIZED, 403);
        assertMapped(Code.INVITE_ROLE_NOT_GRANTABLE, 403);
        assertMapped(Code.INVITE_APPLICATION_NOT_GRANTABLE, 403);
        assertMapped(Code.INVITE_REALM_NOT_ACCESSIBLE, 403);
        assertMapped(Code.INVITE_NOT_FOUND, 404);
        assertMapped(Code.INVITE_NOT_PENDING, 409);
        assertMapped(Code.INVITE_ACCOUNT_REALM_CONFLICT, 409);
        assertMapped(Code.INVITE_EXPIRED, 410);
        assertMapped(Code.INVITE_PROVISIONING_FAILED, 500);
    }

    private void assertMapped(Code code, int status) {
        Response response = mapper.toResponse(new AccessInviteException(code, "message"));
        assertEquals(status, response.getStatus(), code.name());
        RestError error = (RestError) response.getEntity();
        assertEquals(code.name(), error.getErrorCode());
        assertEquals(status, error.getStatus());
    }
}
