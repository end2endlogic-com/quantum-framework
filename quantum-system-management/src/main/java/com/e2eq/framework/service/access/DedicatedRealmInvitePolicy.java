package com.e2eq.framework.service.access;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.service.access.AccessInviteException.Code;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Default policy for dedicated (single-tenant) realms: the realm has one tenant, so the invitee is
 * placed in the realm's registered data domain. This is the behaviour invites had before
 * acceptance policies existed. It refuses to serve a pooled realm, where it would place every
 * invitee in the realm's default tenant.
 */
@ApplicationScoped
public class DedicatedRealmInvitePolicy implements InviteAcceptancePolicy {

    public static final String ID = "dedicated-realm";

    @Inject
    SecurityUtils securityUtils;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validateCreate(InviteCreation creation) {
        requireDedicated(creation.realm());
    }

    @Override
    public InviteGrant resolveGrant(InviteAcceptance acceptance) {
        Realm realm = acceptance.realm();
        requireDedicated(realm);
        DomainContext realmContext = realm.getDomainContext();
        if (realmContext == null) {
            throw new IllegalStateException("Realm '" + realm.getRefName() + "' has no registered data-domain configuration");
        }
        String userId = acceptance.inviteeUserId();
        return InviteGrant.builder()
            .homeContext(homeContext(realm.getRefName()))
            .assignmentDataDomain(DataDomain.builder()
                .orgRefName(realmContext.getOrgRefName())
                .accountNum(realmContext.getAccountId())
                .tenantId(realmContext.getTenantId())
                .ownerId(userId)
                .build())
            .newAssignmentTenantIds(realmContext.getTenantId() == null ? null : List.of(realmContext.getTenantId()))
            .addTenantIds(List.of())
            .sponsoringOrgRefName(realmContext.getOrgRefName())
            .roles(acceptance.invite().getGrantedRoles())
            .authorizedApplications(acceptance.invite().getAuthorizedApplications())
            .tenantId(realmContext.getTenantId())
            .build();
    }

    private void requireDedicated(Realm realm) {
        if (InviteAcceptancePolicies.pooled(realm)) {
            throw new AccessInviteException(Code.INVITE_POLICY_UNAVAILABLE,
                "This realm holds several tenants; its invites need a pooled-realm policy.");
        }
    }

    /** Home context of a new account: the realm, with the framework's default org/account/segment. */
    private DomainContext homeContext(String realm) {
        DataDomain base = securityUtils.getDefaultDataDomain();
        return DomainContext.builder()
            .tenantId(realm)
            .defaultRealm(realm)
            .orgRefName(base.getOrgRefName())
            .accountId(base.getAccountNum())
            .dataSegment(base.getDataSegment())
            .build();
    }
}
