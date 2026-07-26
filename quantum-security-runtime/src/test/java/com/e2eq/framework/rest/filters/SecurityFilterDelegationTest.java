package com.e2eq.framework.rest.filters;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityFilterDelegationTest {

    @Test
    void interactiveUserTokenCannotAssertDelegatedIdentity() {
        assertThrows(ForbiddenException.class, () ->
                SecurityFilter.validateDelegationHeaders(
                        null, null, "susan", null, "michael"));
    }

    @Test
    void serviceTokenMustNameAnEffectiveIdentity() {
        assertThrows(BadRequestException.class, () ->
                SecurityFilter.validateDelegationHeaders(
                        "service", null, null, null, "michael"));
    }

    @Test
    void serviceTokenMayCarryEffectiveAndOriginalIdentity() {
        assertDoesNotThrow(() ->
                SecurityFilter.validateDelegationHeaders(
                        "service", null, "susan", null, "michael"));
    }
}
