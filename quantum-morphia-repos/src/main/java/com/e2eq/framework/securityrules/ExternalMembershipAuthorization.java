package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.security.runtime.RuleContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;

/** Functional permission for creating or deliberately changing an external membership. */
@ApplicationScoped
public class ExternalMembershipAuthorization {
    @Inject RuleContext rules;

    public void require(PrincipalContext caller, String realm, String tenant) {
        if (caller == null || caller.getDataDomain() == null || tenant == null
                || !Objects.equals(realm, caller.getDefaultRealm())
                || !Objects.equals(tenant, caller.getDataDomain().getTenantId())) {
            throw refused();
        }
        var resource = new ResourceContext.Builder().withRealm(realm)
                .withApplicationId(caller.getApplicationId()).withArea("SECURITY")
                .withFunctionalDomain("ACCESS_INVITE").withAction("inviteExternal")
                .withDataDomain(caller.getDataDomain()).build();
        var target = com.e2eq.framework.model.security.AccessInvite.builder()
                .dataDomain(caller.getDataDomain()).build();
        var result = rules.checkRules(caller, resource, com.e2eq.framework.model.security.AccessInvite.class,
                target, RuleEffect.DENY);
        if (result.getFinalEffect() != RuleEffect.ALLOW || result.isScopedConstraintsPresent()
                || result.isFilterConstraintsPresent()) {
            throw refused();
        }
    }

    private static ForbiddenException refused() {
        return new ForbiddenException(Response.status(403).entity(Map.of(
                "errorCode", "INVITE_EXTERNAL_NOT_AUTHORIZED",
                "reasonMessage", "inviteExternal permission in the selected tenant is required")).build());
    }
}
