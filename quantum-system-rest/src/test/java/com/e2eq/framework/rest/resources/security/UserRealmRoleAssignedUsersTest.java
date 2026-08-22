package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRealmRoleAssignedUsersTest {

    @Test
    void keepsOnlyActiveAssignmentsThatGrantTheApplication() {
        UserRealmRole mingardia = assignment("mingardia", "helixor-code-P1",
                List.of("helixor-code", "quantum-b2bi"), UserRealmRole.STATUS_ACTIVE);
        UserRealmRole otherApp = assignment("pat", "helixor-code-P1",
                List.of("helixor-code"), UserRealmRole.STATUS_ACTIVE);
        UserRealmRole suspended = assignment("lee", "helixor-code-P1",
                List.of("quantum-b2bi"), UserRealmRole.STATUS_SUSPENDED);
        UserRealmRole wildcard = assignment("sam", "helixor-code-P1",
                List.of("*"), UserRealmRole.STATUS_ACTIVE);

        List<UserRealmRole> granted = UserRealmRoleResource.assignmentsGrantingApplication(
                List.of(mingardia, otherApp, suspended, wildcard), "quantum-b2bi");

        assertEquals(List.of("mingardia", "sam"),
                granted.stream().map(UserRealmRole::getUserId).toList());
    }

    @Test
    void emptyGrantIsNotAnApplicationAssignment() {
        UserRealmRole unscoped = assignment("unscoped", "helixor-code-P1",
                List.of(), UserRealmRole.STATUS_ACTIVE);

        assertTrue(UserRealmRoleResource.assignmentsGrantingApplication(
                List.of(unscoped), "quantum-b2bi").isEmpty());
    }

    private static UserRealmRole assignment(
            String userId, String realm, List<String> applications, String status) {
        UserRealmRole role = new UserRealmRole();
        role.setUserId(userId);
        role.setRealmRefName(realm);
        role.setAuthorizedApplications(applications);
        role.setStatus(status);
        return role;
    }
}
