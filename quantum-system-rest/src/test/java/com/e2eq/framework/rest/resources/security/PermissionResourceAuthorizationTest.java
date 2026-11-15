package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.service.access.AccessInviteService;
import jakarta.enterprise.inject.Vetoed;
import com.e2eq.framework.model.security.ApplicationAccessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unwired lookup dependencies deliberately fail if a refusal ever reaches target resolution. */
class PermissionResourceAuthorizationTest {
    @Vetoed
    static class NoAdministrativeAuthority extends AccessInviteService {
        @Override
        public boolean mayInspectPermissions(String realm, String user, String tenant) {
            return false;
        }
    }

    @Test
    void refusesEveryInspectionBeforeAnyTargetLookup() {
        PermissionResource resource = new PermissionResource();
        resource.accessInviteService = new NoAdministrativeAuthority();
        var caller = new PrincipalContext.Builder().withUserId("alice").withDefaultRealm("own")
                .withScope("AUTHENTICATED").withRoles(new String[]{"user"})
                .withDataDomain(new DataDomain("org", "acct", "tenant", 0, "alice")).build();
        SecurityCallScope.runWithContexts(caller, new ResourceContext.Builder().withRealm("own")
                .withArea("system").withFunctionalDomain("permission").withAction("check").build(), () -> {
            for (String target : List.of("bob", "nonexistent")) {
                var provenance = new PermissionResource.RoleProvenanceRequest();
                provenance.userId = target;
                provenance.realm = "own";
                refused(() -> resource.roleProvenance(provenance));
                var check = new PermissionResource.CheckRequest();
                check.identity = target;
                check.realm = "own";
                refused(() -> resource.check(check));
                refused(() -> resource.checkWithIndex(check));
                var evaluate = new PermissionResource.EvaluateRequest();
                evaluate.identity = target;
                evaluate.realm = "own";
                refused(() -> resource.evaluateFunctionalAccess(true, evaluate));
            }
            return null;
        });
    }

    private void refused(org.junit.jupiter.api.function.Executable call) {
        ApplicationAccessException exception = assertThrows(ApplicationAccessException.class, call);
        assertEquals(403, exception.code().httpStatus());
        assertEquals(ApplicationAccessException.Code.PERMISSION_INSPECTION_FORBIDDEN, exception.code());
        assertEquals("PERMISSION_INSPECTION_FORBIDDEN: You may not inspect permissions for the requested scope.",
                exception.publicMessage());
    }
}
