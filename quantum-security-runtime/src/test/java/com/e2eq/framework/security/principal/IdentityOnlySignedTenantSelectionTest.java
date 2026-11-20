package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * At the identity authority an identity-only (v2) access token takes the signed path with no realm,
 * tenant or roles claim. The tenant is then selected as identity mode selects it (the requested
 * tenant, else the identity's default), never read from a claim, and a v1 token keeps its claim.
 */
class IdentityOnlySignedTenantSelectionTest {

    private final PrincipalFixture world = new PrincipalFixture();

    private PrincipalContextRequest.Builder v2(String userId, String tenant, boolean exempt) {
        CredentialUserIdPassword credential = world.credential(userId);
        var claims = new PrincipalContextRequest.SignedClaims(null, null, null, false, null, null, null, exempt, true);
        return PrincipalContextRequest.signedTokenClaims(credential.getSubject(), claims)
                .applicationId(PrincipalFixture.APP).userIdCandidate(credential.getUserId()).realm("pooled")
                .tenantId(tenant);
    }

    private PrincipalContext resolve(PrincipalContextRequest.Builder request) {
        return world.resolver().resolve(request.build()).context();
    }

    @Test
    void theIdentitysDefaultTenantIsSelected() {
        world.credential("alice@acme").setDataDomainPolicy(
                PrincipalFixture.policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "tenant-a"));
        assertEquals("tenant-a", resolve(v2("alice@acme", null, false)).getDataDomain().getTenantId());
    }

    @Test
    void aRequestedTenantWinsOverTheDefault() {
        world.credential("alice@acme").setDataDomainPolicy(
                PrincipalFixture.policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "tenant-a"));
        assertEquals("tenant-b", resolve(v2("alice@acme", "tenant-b", false)).getDataDomain().getTenantId());
    }

    @Test
    void severalTenantsWithoutADefaultRequireSelection() {
        PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class,
                () -> resolve(v2("alice@acme", null, false)));
        assertEquals(PrincipalResolutionError.TENANT_SELECTION_REQUIRED, refusal.error());
    }

    @Test
    void aPlatformOperationSelectsNoTenantWhenNoneIsRequested() {
        // Several reachable tenants and no default: selection would refuse, but none is needed.
        resolve(v2("alice@acme", null, true));
    }

    @Test
    void aDisabledCredentialsV2TokenIsRefusedAtTheAuthority() {
        world.credential("alice@acme").setDataDomainPolicy(
                PrincipalFixture.policy(DataDomainPolicyEntry.ResolutionMode.FIXED, "tenant-a"));
        for (ActiveStatus status : new ActiveStatus[] {ActiveStatus.INACTIVE, ActiveStatus.DELETED}) {
            world.credential("alice@acme").setActiveStatus(status);
            PrincipalResolutionException refusal = assertThrows(PrincipalResolutionException.class,
                    () -> resolve(v2("alice@acme", null, false)));
            assertEquals(PrincipalResolutionError.PRINCIPAL_DISABLED, refusal.error());
        }
    }

    @Test
    void anIdentityOnlyTokenCarriesNoRealmTenantOrRoles() {
        assertThrows(IllegalArgumentException.class, () -> new PrincipalContextRequest.SignedClaims(
                "pooled", null, null, false, null, null, null, false, true));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalContextRequest.SignedClaims(
                null, "tenant-a", null, false, null, null, null, false, true));
    }
}
