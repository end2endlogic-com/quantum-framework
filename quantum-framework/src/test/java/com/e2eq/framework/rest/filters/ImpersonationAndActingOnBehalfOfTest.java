package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Impersonation (X-Impersonate-*) and 
 * Acting-On-Behalf-Of (X-Acting-On-Behalf-Of-*) functionality.
 * 
 * These tests verify the PrincipalContext behavior for these security features:
 * 
 * 1. Impersonation: The caller "becomes" another identity with that identity's
 *    permissions, DataDomain, and realm. Original caller is tracked for audit.
 * 
 * 2. Acting-On-Behalf-Of: The caller retains their own identity and permissions
 *    but records that they are acting on behalf of another party (audit only).
 */
public class ImpersonationAndActingOnBehalfOfTest {

    // Test data factories
    private DataDomain createSystemDomain() {
        return DataDomain.builder()
                .orgRefName("SYSTEM")
                .accountNum("SYS-001")
                .tenantId("system-com")
                .dataSegment(0)
                .ownerId("system-admin")
                .build();
    }

    private DataDomain createTargetUserDomain() {
        return DataDomain.builder()
                .orgRefName("ACME")
                .accountNum("ACME-001")
                .tenantId("acme-com")
                .dataSegment(0)
                .ownerId("john@acme.com")
                .build();
    }

    private DomainContext createTargetDomainContext() {
        return DomainContext.builder()
                .tenantId("acme-com")
                .orgRefName("ACME")
                .accountId("ACME-001")
                .defaultRealm("acme-com")
                .dataSegment(0)
                .build();
    }

    private PrincipalContext createCallerContext() {
        return new PrincipalContext.Builder()
                .withUserId("admin@system.com")
                .withDefaultRealm("system-com")
                .withDataDomain(createSystemDomain())
                .withRoles(new String[]{"ADMIN", "SUPER_USER"})
                .withScope("AUTHENTICATED")
                .build();
    }

    @Nested
    @DisplayName("PrincipalContext Builder Tests for Impersonation")
    class ImpersonationBuilderTests {

        @Test
        @DisplayName("Builder should support impersonatedBySubject and impersonatedByUserId")
        void builderSupportsImpersonationFields() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("target-user")
                    .withDefaultRealm("target-realm")
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(new String[]{"USER"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedBySubject("original-subject-uuid")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            assertEquals("original-subject-uuid", context.getImpersonatedBySubject());
            assertEquals("admin@system.com", context.getImpersonatedByUserId());
        }

        @Test
        @DisplayName("Impersonation should change userId to target user")
        void impersonationChangesUserId() {
            // When impersonating, the PrincipalContext userId becomes the target user
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")  // Target user's ID
                    .withDefaultRealm("acme-com")
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(new String[]{"USER", "EDITOR"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedBySubject("admin-subject-uuid")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            assertEquals("john@acme.com", impersonatedContext.getUserId());
            assertNotEquals("admin@system.com", impersonatedContext.getUserId());
        }

        @Test
        @DisplayName("Impersonation should use target user's DataDomain")
        void impersonationUsesTargetDataDomain() {
            DataDomain targetDomain = createTargetUserDomain();
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")
                    .withDefaultRealm("acme-com")
                    .withDataDomain(targetDomain)
                    .withRoles(new String[]{"USER"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedBySubject("admin-subject-uuid")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            assertEquals("ACME", impersonatedContext.getDataDomain().getOrgRefName());
            assertEquals("acme-com", impersonatedContext.getDataDomain().getTenantId());
            assertEquals("john@acme.com", impersonatedContext.getDataDomain().getOwnerId());
        }

        @Test
        @DisplayName("Impersonation should use target user's realm")
        void impersonationUsesTargetRealm() {
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")
                    .withDefaultRealm("acme-com")  // Target user's realm
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(new String[]{"USER"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            assertEquals("acme-com", impersonatedContext.getDefaultRealm());
        }

        @Test
        @DisplayName("Impersonation should use target user's roles")
        void impersonationUsesTargetRoles() {
            String[] targetRoles = new String[]{"USER", "EDITOR", "VIEWER"};
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")
                    .withDefaultRealm("acme-com")
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(targetRoles)
                    .withScope("AUTHENTICATED")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            assertArrayEquals(targetRoles, impersonatedContext.getRoles());
        }
    }

    @Nested
    @DisplayName("PrincipalContext Builder Tests for Acting-On-Behalf-Of")
    class ActingOnBehalfOfBuilderTests {

        @Test
        @DisplayName("Builder should support actingOnBehalfOfSubject and actingOnBehalfOfUserId")
        void builderSupportsActingOnBehalfOfFields() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("admin@system.com")
                    .withDefaultRealm("system-com")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"ADMIN"})
                    .withScope("AUTHENTICATED")
                    .withActingOnBehalfOfSubject("customer-subject-uuid")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            assertEquals("customer-subject-uuid", context.getActingOnBehalfOfSubject());
            assertEquals("customer@acme.com", context.getActingOnBehalfOfUserId());
        }

        @Test
        @DisplayName("Acting-On-Behalf-Of should NOT change userId")
        void actingOnBehalfOfDoesNotChangeUserId() {
            // Acting on behalf of is audit-only - the caller's identity remains unchanged
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("support@system.com")  // Caller remains themselves
                    .withDefaultRealm("system-com")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"SUPPORT"})
                    .withScope("AUTHENTICATED")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            assertEquals("support@system.com", context.getUserId());
            assertNotEquals("customer@acme.com", context.getUserId());
        }

        @Test
        @DisplayName("Acting-On-Behalf-Of should NOT change DataDomain")
        void actingOnBehalfOfDoesNotChangeDataDomain() {
            DataDomain callerDomain = createSystemDomain();
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("support@system.com")
                    .withDefaultRealm("system-com")
                    .withDataDomain(callerDomain)
                    .withRoles(new String[]{"SUPPORT"})
                    .withScope("AUTHENTICATED")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            // DataDomain should still be the caller's, not the target's
            assertEquals("SYSTEM", context.getDataDomain().getOrgRefName());
            assertEquals("system-com", context.getDataDomain().getTenantId());
        }

        @Test
        @DisplayName("Acting-On-Behalf-Of should NOT change realm")
        void actingOnBehalfOfDoesNotChangeRealm() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("support@system.com")
                    .withDefaultRealm("system-com")  // Caller's realm
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"SUPPORT"})
                    .withScope("AUTHENTICATED")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            assertEquals("system-com", context.getDefaultRealm());
        }

        @Test
        @DisplayName("Acting-On-Behalf-Of should NOT change roles")
        void actingOnBehalfOfDoesNotChangeRoles() {
            String[] callerRoles = new String[]{"SUPPORT", "VIEWER"};
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("support@system.com")
                    .withDefaultRealm("system-com")
                    .withDataDomain(createSystemDomain())
                    .withRoles(callerRoles)
                    .withScope("AUTHENTICATED")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            assertArrayEquals(callerRoles, context.getRoles());
        }
    }

    @Nested
    @DisplayName("Combined Impersonation and Acting-On-Behalf-Of Tests")
    class CombinedTests {

        @Test
        @DisplayName("Impersonation and Acting-On-Behalf-Of can coexist")
        void impersonationAndActingOnBehalfOfCanCoexist() {
            // Scenario: Admin impersonates a user, who is acting on behalf of a customer
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")  // Impersonated user
                    .withDefaultRealm("acme-com")
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(new String[]{"USER"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedBySubject("admin-subject-uuid")
                    .withImpersonatedByUserId("admin@system.com")
                    .withActingOnBehalfOfSubject("customer-subject-uuid")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            // Identity is the impersonated user
            assertEquals("john@acme.com", context.getUserId());
            assertEquals("acme-com", context.getDefaultRealm());
            
            // Impersonation audit fields
            assertEquals("admin@system.com", context.getImpersonatedByUserId());
            assertEquals("admin-subject-uuid", context.getImpersonatedBySubject());
            
            // Acting-on-behalf-of audit fields
            assertEquals("customer@acme.com", context.getActingOnBehalfOfUserId());
            assertEquals("customer-subject-uuid", context.getActingOnBehalfOfSubject());
        }

        @Test
        @DisplayName("equals() and hashCode() include impersonation and actingOnBehalfOf fields")
        void equalsAndHashCodeIncludeAllFields() {
            PrincipalContext context1 = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedByUserId("admin")
                    .withActingOnBehalfOfUserId("customer")
                    .build();

            PrincipalContext context2 = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedByUserId("admin")
                    .withActingOnBehalfOfUserId("customer")
                    .build();

            PrincipalContext context3 = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedByUserId("different-admin")  // Different
                    .withActingOnBehalfOfUserId("customer")
                    .build();

            assertEquals(context1, context2);
            assertEquals(context1.hashCode(), context2.hashCode());
            assertNotEquals(context1, context3);
        }

        @Test
        @DisplayName("toString() includes impersonation and actingOnBehalfOf fields")
        void toStringIncludesAllFields() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("user@test.com")
                    .withDefaultRealm("test-realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedByUserId("admin@system.com")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            String str = context.toString();
            assertTrue(str.contains("impersonatedByUserId"));
            assertTrue(str.contains("admin@system.com"));
            assertTrue(str.contains("actingOnBehalfOfUserId"));
            assertTrue(str.contains("customer@acme.com"));
        }
    }

    @Nested
    @DisplayName("DomainContext to DataDomain Conversion Tests")
    class DomainContextConversionTests {

        @Test
        @DisplayName("DomainContext.toDataDomain() creates correct DataDomain")
        void domainContextToDataDomainCreatesCorrectDataDomain() {
            DomainContext domainContext = createTargetDomainContext();
            String ownerId = "john@acme.com";
            
            DataDomain dataDomain = domainContext.toDataDomain(ownerId);

            assertEquals("acme-com", dataDomain.getTenantId());
            assertEquals("ACME", dataDomain.getOrgRefName());
            assertEquals("ACME-001", dataDomain.getAccountNum());
            assertEquals("john@acme.com", dataDomain.getOwnerId());
            assertEquals(0, dataDomain.getDataSegment());
        }

        @Test
        @DisplayName("DomainContext.toDataDomain() preserves ownerId from parameter")
        void domainContextToDataDomainPreservesOwnerId() {
            DomainContext domainContext = createTargetDomainContext();
            
            // When impersonating, ownerId should be the impersonated user
            DataDomain impersonatedDomain = domainContext.toDataDomain("impersonated-user@acme.com");
            assertEquals("impersonated-user@acme.com", impersonatedDomain.getOwnerId());
            
            // When using X-Realm, ownerId should be the original caller
            DataDomain xRealmDomain = domainContext.toDataDomain("admin@system.com");
            assertEquals("admin@system.com", xRealmDomain.getOwnerId());
        }
    }

    @Nested
    @DisplayName("Edge Cases and Null Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Null impersonation fields should remain null")
        void nullImpersonationFieldsShouldRemainNull() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .build();

            assertNull(context.getImpersonatedBySubject());
            assertNull(context.getImpersonatedByUserId());
            assertNull(context.getActingOnBehalfOfSubject());
            assertNull(context.getActingOnBehalfOfUserId());
        }

        @Test
        @DisplayName("Only subject can be set without userId for impersonation")
        void onlySubjectCanBeSetForImpersonation() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedBySubject("subject-only")
                    .build();

            assertEquals("subject-only", context.getImpersonatedBySubject());
            assertNull(context.getImpersonatedByUserId());
        }

        @Test
        @DisplayName("Only userId can be set without subject for impersonation")
        void onlyUserIdCanBeSetForImpersonation() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedByUserId("userId-only")
                    .build();

            assertNull(context.getImpersonatedBySubject());
            assertEquals("userId-only", context.getImpersonatedByUserId());
        }

        @Test
        @DisplayName("Only subject can be set without userId for acting-on-behalf-of")
        void onlySubjectCanBeSetForActingOnBehalfOf() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withActingOnBehalfOfSubject("subject-only")
                    .build();

            assertEquals("subject-only", context.getActingOnBehalfOfSubject());
            assertNull(context.getActingOnBehalfOfUserId());
        }

        @Test
        @DisplayName("Only userId can be set without subject for acting-on-behalf-of")
        void onlyUserIdCanBeSetForActingOnBehalfOf() {
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("user")
                    .withDefaultRealm("realm")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"USER"})
                    .withActingOnBehalfOfUserId("userId-only")
                    .build();

            assertNull(context.getActingOnBehalfOfSubject());
            assertEquals("userId-only", context.getActingOnBehalfOfUserId());
        }
    }

    @Nested
    @DisplayName("Semantic Behavior Tests")
    class SemanticBehaviorTests {

        @Test
        @DisplayName("Impersonation: effective identity is the target user")
        void impersonationEffectiveIdentityIsTargetUser() {
            // Scenario: System admin impersonates John
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")
                    .withDefaultRealm("acme-com")
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(new String[]{"USER", "EDITOR"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            // The "effective" identity for permission checks is John
            assertEquals("john@acme.com", impersonatedContext.getUserId());
            
            // Permissions should be evaluated against John's roles
            assertArrayEquals(new String[]{"USER", "EDITOR"}, impersonatedContext.getRoles());
            
            // Data should be scoped to John's DataDomain
            assertEquals("ACME", impersonatedContext.getDataDomain().getOrgRefName());
            assertEquals("acme-com", impersonatedContext.getDataDomain().getTenantId());
        }

        @Test
        @DisplayName("Acting-On-Behalf-Of: effective identity remains the caller")
        void actingOnBehalfOfEffectiveIdentityRemainsCaller() {
            // Scenario: Support agent acts on behalf of customer
            PrincipalContext context = new PrincipalContext.Builder()
                    .withUserId("support@system.com")
                    .withDefaultRealm("system-com")
                    .withDataDomain(createSystemDomain())
                    .withRoles(new String[]{"SUPPORT", "VIEWER"})
                    .withScope("AUTHENTICATED")
                    .withActingOnBehalfOfUserId("customer@acme.com")
                    .build();

            // The "effective" identity for permission checks is still the support agent
            assertEquals("support@system.com", context.getUserId());
            
            // Permissions should be evaluated against the support agent's roles
            assertArrayEquals(new String[]{"SUPPORT", "VIEWER"}, context.getRoles());
            
            // Data should be scoped to the support agent's DataDomain
            assertEquals("SYSTEM", context.getDataDomain().getOrgRefName());
            assertEquals("system-com", context.getDataDomain().getTenantId());
            
            // But the acting-on-behalf-of information is available for audit
            assertEquals("customer@acme.com", context.getActingOnBehalfOfUserId());
        }

        @Test
        @DisplayName("Impersonation records original caller for audit trail")
        void impersonationRecordsOriginalCallerForAudit() {
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withUserId("john@acme.com")
                    .withDefaultRealm("acme-com")
                    .withDataDomain(createTargetUserDomain())
                    .withRoles(new String[]{"USER"})
                    .withImpersonatedBySubject("admin-uuid-1234")
                    .withImpersonatedByUserId("admin@system.com")
                    .build();

            // Audit trail should show who did the impersonation
            assertEquals("admin@system.com", impersonatedContext.getImpersonatedByUserId());
            assertEquals("admin-uuid-1234", impersonatedContext.getImpersonatedBySubject());
        }
    }
}

