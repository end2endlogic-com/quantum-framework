package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A system principal administers the control-plane directory in every tenant: it may write a
 * realm membership whose record belongs to another tenant. A service identity holding
 * {@code system} and a tenant admin stay confined to their own tenant's records.
 */
class SystemPrincipalDirectoryRuleTest {

    /** A realm membership as the control-plane directory stores it. */
    static class Membership extends UnversionedBaseModel {
        @Override
        public String bmFunctionalDomain() { return RuleContext.CONTROL_PLANE_DIRECTORY_DOMAIN; }
        @Override
        public String bmFunctionalArea() { return RuleContext.CONTROL_PLANE_DIRECTORY_AREA; }
    }

    RuleContext ruleContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ruleContext = new RuleContext();
        ruleContext.clear();
        Instance<AccessListResolver> none = mock(Instance.class);
        when(none.iterator()).thenAnswer(inv -> List.<AccessListResolver>of().iterator());
        ruleContext.resolvers = none;
        // The built-in tenant-admin rule, as addSystemRules adds it.
        SecurityURIHeader adminHeader = new SecurityURIHeader.Builder()
                .withIdentity("admin").withArea("*").withFunctionalDomain("*").withAction("*").build();
        ruleContext.addRule(adminHeader, new Rule.Builder()
                .withName("tenant admin can administer the tenant records")
                .withSecurityURI(new SecurityURI(adminHeader, anyBody()))
                .withAndFilterString("dataDomain.tenantId:${pTenantId}")
                .withEffect(RuleEffect.ALLOW)
                .withPriority(0)
                .withFinalRule(true)
                .build());
        ruleContext.addSystemPrincipalDirectoryRule();
    }

    @Test
    void aSystemPrincipalWritesAMembershipOfAnotherTenant() {
        PrincipalContext operator = principal("operator", "system-tenant", null, "system", "admin", "user");
        assertDoesNotThrow(() -> write(operator, membershipIn("helixor-code-D1")));
    }

    @Test
    void aServiceIdentityHoldingSystemStaysInItsOwnTenant() {
        PrincipalContext service = principal("svc-app", "app-tenant", PrincipalContext.SCOPE_SERVICE,
                "system", "admin");
        assertThrows(SecurityCheckException.class, () -> write(service, membershipIn("helixor-code-D1")));
        assertDoesNotThrow(() -> write(service, membershipIn("app-tenant")));
    }

    @Test
    void aTenantAdminStaysInItsOwnTenant() {
        PrincipalContext admin = principal("tenant-admin", "tenant-a", null, "admin", "user");
        assertThrows(SecurityCheckException.class, () -> write(admin, membershipIn("helixor-code-D1")));
        assertDoesNotThrow(() -> write(admin, membershipIn("tenant-a")));
    }

    @Test
    void theRuleCoversOnlyTheControlPlaneDirectory() {
        PrincipalContext operator = principal("operator", "system-tenant", null, "system");
        ResourceContext elsewhere = new ResourceContext.Builder()
                .withArea("widget_hub").withFunctionalDomain("widgets").withAction("save").build();
        assertEquals(RuleEffect.DENY, ruleContext.checkRules(operator, elsewhere).getFinalEffect());
    }

    private void write(PrincipalContext principal, Membership record) {
        ResourceContext save = new ResourceContext.Builder()
                .withArea(RuleContext.CONTROL_PLANE_DIRECTORY_AREA)
                .withFunctionalDomain(RuleContext.CONTROL_PLANE_DIRECTORY_DOMAIN)
                .withAction("UPDATE").build();
        SecurityCheckResponse response = ruleContext.checkRules(principal, save);
        assertEquals(RuleEffect.ALLOW, response.getFinalEffect(), "the caller reaches the write");
        ruleContext.enforceWriteFilters(principal, save, response, record);
    }

    private static Membership membershipIn(String tenantId) {
        Membership membership = new Membership();
        membership.setDataDomain(new DataDomain("HelixorAI", "0000000001", tenantId, 0, "svc-x"));
        return membership;
    }

    private static PrincipalContext principal(String userId, String tenantId, String scope, String... roles) {
        PrincipalContext.Builder builder = new PrincipalContext.Builder()
                .withDefaultRealm("quantum-system")
                .withDataDomain(new DataDomain("HelixorAI", "0000000001", tenantId, 0, userId))
                .withUserId(userId)
                .withRoles(roles);
        if (scope != null) {
            builder.withScope(scope);
        }
        return builder.build();
    }

    private static SecurityURIBody anyBody() {
        return new SecurityURIBody.Builder()
                .withOrgRefName("*").withAccountNumber("*").withRealm("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").build();
    }
}
