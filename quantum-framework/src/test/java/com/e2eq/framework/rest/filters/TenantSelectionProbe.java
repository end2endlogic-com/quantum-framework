package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.system.membership.RealmMembershipService;

/** Test access to the pooled-realm tenant selection a request goes through after sign-in. */
public final class TenantSelectionProbe {

    private TenantSelectionProbe() {
    }

    public static PrincipalContext select(SystemDirectory directory, RealmMembershipService memberships,
                                          PrincipalContext signedIn, String headerTenantId, String tokenTenantId) {
        SecurityFilter filter = new SecurityFilter();
        filter.systemDirectory = directory;
        filter.realmMembershipService = memberships;
        return filter.applyTenantSelection(signedIn, headerTenantId, tokenTenantId);
    }
}
