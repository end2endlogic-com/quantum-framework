package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.persistent.base.DataDomain;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalMembershipAuthorizationTest {
    @Test
    void functionalPermissionUsesTheCallerAndTenantAndFailsClosed() {
        var authorization = new ExternalMembershipAuthorization();
        authorization.rules = mock(RuleContext.class);
        var caller = new PrincipalContext.Builder().withDefaultRealm("realm").withUserId("person")
                .withRoles(new String[]{"partner-manager"})
                .withDataDomain(new DataDomain("org", "account", "tenant", 0, "person")).build();
        var decision = new SecurityCheckResponse();
        decision.setFinalEffect(RuleEffect.DENY);
        when(authorization.rules.checkRules(eq(caller), any(ResourceContext.class), eq(AccessInvite.class),
                any(), eq(RuleEffect.DENY))).thenAnswer(call -> {
                    ResourceContext resource = call.getArgument(1);
                    assertEquals("security", resource.getArea());
                    assertEquals("access_invite", resource.getFunctionalDomain());
                    assertEquals("inviteexternal", resource.getAction());
                    assertEquals("tenant", resource.getDataDomain().getTenantId());
                    return decision;
                });
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> authorization.require(caller, "realm", "tenant"));
        decision.setFinalEffect(RuleEffect.ALLOW);
        assertDoesNotThrow(() -> authorization.require(caller, "realm", "tenant"));
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> authorization.require(caller, "realm", "another"));
        decision.setScopedConstraintsPresent(true);
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> authorization.require(caller, "realm", "tenant"));
    }
}
