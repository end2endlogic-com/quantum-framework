package com.e2eq.framework.service.access;

import jakarta.enterprise.context.ApplicationScoped;

/** Test-only acceptance policy that accepts creation and fails while resolving the grant. */
@ApplicationScoped
public class ThrowingTestInvitePolicy implements InviteAcceptancePolicy {

    public static final String ID = "test-throwing-policy";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validateCreate(InviteCreation creation) {
    }

    @Override
    public com.e2eq.framework.model.persistent.base.DataDomain inviteRecordDataDomain(InviteCreation creation) {
        return PooledRealmTenantInvitePolicy.copy(creation.scope().getDataDomain(), creation.inviterUserId());
    }

    @Override
    public InviteGrant resolveGrant(InviteAcceptance acceptance) {
        throw new IllegalStateException("simulated policy failure");
    }
}
