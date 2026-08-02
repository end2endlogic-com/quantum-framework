package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.UserGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityRoleResolverApplicationScopeTest {

    @Test
    void groupRolesDoNotCrossApplicationBoundaries() {
        UserGroup scheduler = new UserGroup();
        scheduler.setApplicationId("scheduler");

        assertTrue(IdentityRoleResolver.groupAppliesToApplication(scheduler, "scheduler"));
        assertFalse(IdentityRoleResolver.groupAppliesToApplication(scheduler, "reporting"));
    }

    @Test
    void wildcardGroupIsExplicitlyGlobalButLegacyGroupIsNot() {
        UserGroup wildcard = new UserGroup();
        wildcard.setApplicationId("*");
        UserGroup legacy = new UserGroup();

        assertTrue(IdentityRoleResolver.groupAppliesToApplication(wildcard, "scheduler"));
        assertFalse(IdentityRoleResolver.groupAppliesToApplication(legacy, "scheduler"));
    }
}
