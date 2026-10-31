package com.e2eq.framework.rest.filters;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ServicePassportAudienceAdmissionTest {
    @Test void validServicePassportIsAdmittedWithoutAUserAudience() {
        assertDoesNotThrow(() -> SecurityFilter.admitAudience(Optional.of("app"),true,"service",Set.of()));
        assertThrows(jakarta.ws.rs.ForbiddenException.class,() -> SecurityFilter.admitAudience(Optional.of("app"),true,"service",Set.of("*")));
    }
    @Test void userAudienceStillRequiresTheTargetApplication() {
        assertDoesNotThrow(() -> SecurityFilter.admitAudience(Optional.of("app"),true,"user",Set.of("app")));
        assertThrows(jakarta.ws.rs.ForbiddenException.class,() -> SecurityFilter.admitAudience(Optional.of("app"),true,"user",Set.of()));
        assertThrows(jakarta.ws.rs.ForbiddenException.class,() -> SecurityFilter.admitAudience(Optional.of("app"),true,"user",Set.of("other")));
    }
}
