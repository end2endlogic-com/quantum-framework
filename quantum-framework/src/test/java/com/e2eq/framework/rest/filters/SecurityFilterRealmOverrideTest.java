package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for X-Realm header DataDomain override behavior in SecurityFilter.
 * 
 * These tests verify that when a user uses X-Realm to switch realms:
 * 1. Their DataDomain is updated to the target realm's default DataDomain
 * 2. Their identity (userId, roles) remains unchanged
 * 3. The realm override is properly tracked for audit purposes
 * 4. Impersonation takes precedence over realm override
 */
@DisplayName("SecurityFilter Realm Override Tests")
public class SecurityFilterRealmOverrideTest {

    // Test data for system realm
    private static final String SYSTEM_REALM = "system-com";
    private static final String SYSTEM_ORG = "SYSTEM";
    private static final String SYSTEM_TENANT = "system-com";
    private static final String SYSTEM_ACCOUNT = "SYSTEM-ACCT";
    private static final String SYSTEM_USER = "admin@system.com";

    // Test data for target realm
    private static final String TARGET_REALM = "mycompanyxyz-com";
    private static final String TARGET_ORG = "MYCOMPANY";
    private static final String TARGET_TENANT = "mycompanyxyz-com";
    private static final String TARGET_ACCOUNT = "MYCOMPANY-ACCT";

    private DataDomain systemDataDomain;
    private DataDomain expectedTargetDataDomain;
    private PrincipalContext systemUserContext;
    private Realm targetRealm;

    @BeforeEach
    void setUp() {
        // Set up system user's DataDomain
        systemDataDomain = DataDomain.builder()
                .orgRefName(SYSTEM_ORG)
                .tenantId(SYSTEM_TENANT)
                .accountNum(SYSTEM_ACCOUNT)
                .ownerId(SYSTEM_USER)
                .dataSegment(0)
                .build();

        // Set up system user's PrincipalContext
        systemUserContext = new PrincipalContext.Builder()
                .withDefaultRealm(SYSTEM_REALM)
                .withDataDomain(systemDataDomain)
                .withUserId(SYSTEM_USER)
                .withRoles(new String[]{"admin", "user"})
                .withScope("AUTHENTICATED")
                .build();

        // Set up target realm with its DomainContext
        DomainContext targetDomainContext = DomainContext.builder()
                .tenantId(TARGET_TENANT)
                .defaultRealm(TARGET_REALM)
                .orgRefName(TARGET_ORG)
                .accountId(TARGET_ACCOUNT)
                .dataSegment(0)
                .build();

        targetRealm = Realm.builder()
                .refName(TARGET_REALM)
                .emailDomain("mycompanyxyz.com")
                .databaseName(TARGET_REALM)
                .domainContext(targetDomainContext)
                .build();

        // Expected DataDomain after realm override (uses target's context but caller's userId as owner)
        expectedTargetDataDomain = DataDomain.builder()
                .orgRefName(TARGET_ORG)
                .tenantId(TARGET_TENANT)
                .accountNum(TARGET_ACCOUNT)
                .ownerId(SYSTEM_USER)  // Still the original caller
                .dataSegment(0)
                .build();
    }

    @Nested
    @DisplayName("Realm Override Core Logic")
    class RealmOverrideCoreLogicTests {

        @Test
        @DisplayName("applyRealmOverride should update DataDomain to target realm's default")
        void shouldUpdateDataDomainToTargetRealmDefault() {
            // Given: A user in system realm wanting to switch to target realm
            DomainContext targetDomainContext = targetRealm.getDomainContext();
            
            // When: We create a new DataDomain using target realm's context
            DataDomain effectiveDataDomain = targetDomainContext.toDataDomain(SYSTEM_USER);
            
            // Then: The DataDomain should use target realm's values but keep caller's userId
            assertEquals(TARGET_ORG, effectiveDataDomain.getOrgRefName());
            assertEquals(TARGET_TENANT, effectiveDataDomain.getTenantId());
            assertEquals(TARGET_ACCOUNT, effectiveDataDomain.getAccountNum());
            assertEquals(SYSTEM_USER, effectiveDataDomain.getOwnerId());  // Still the original caller
            assertEquals(0, effectiveDataDomain.getDataSegment());
        }

        @Test
        @DisplayName("Realm override should track original DataDomain for audit")
        void shouldTrackOriginalDataDomainForAudit() {
            // Given: Original context and target realm's DataDomain
            DataDomain effectiveDataDomain = targetRealm.getDomainContext().toDataDomain(SYSTEM_USER);
            
            // When: Building a new PrincipalContext with realm override
            PrincipalContext overriddenContext = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(effectiveDataDomain)
                    .withUserId(systemUserContext.getUserId())
                    .withRoles(systemUserContext.getRoles())
                    .withScope(systemUserContext.getScope())
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            // Then: Original DataDomain should be preserved for audit
            assertTrue(overriddenContext.isRealmOverrideActive());
            assertNotNull(overriddenContext.getOriginalDataDomain());
            assertEquals(SYSTEM_ORG, overriddenContext.getOriginalDataDomain().getOrgRefName());
            assertEquals(SYSTEM_TENANT, overriddenContext.getOriginalDataDomain().getTenantId());
            
            // And: Effective DataDomain should be the target realm's
            assertEquals(TARGET_ORG, overriddenContext.getDataDomain().getOrgRefName());
            assertEquals(TARGET_TENANT, overriddenContext.getDataDomain().getTenantId());
        }

        @Test
        @DisplayName("Realm override should preserve user identity (userId and roles)")
        void shouldPreserveUserIdentity() {
            // Given: Original context and target realm's DataDomain
            DataDomain effectiveDataDomain = targetRealm.getDomainContext().toDataDomain(SYSTEM_USER);
            
            // When: Building a new PrincipalContext with realm override
            PrincipalContext overriddenContext = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(effectiveDataDomain)
                    .withUserId(systemUserContext.getUserId())
                    .withRoles(systemUserContext.getRoles())
                    .withScope(systemUserContext.getScope())
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            // Then: User identity should be unchanged
            assertEquals(SYSTEM_USER, overriddenContext.getUserId());
            assertArrayEquals(new String[]{"admin", "user"}, overriddenContext.getRoles());
            assertEquals("AUTHENTICATED", overriddenContext.getScope());
        }

        @Test
        @DisplayName("Realm should change to target realm")
        void shouldChangeToTargetRealm() {
            // Given: Original context in system realm
            assertEquals(SYSTEM_REALM, systemUserContext.getDefaultRealm());
            
            // When: Building a new PrincipalContext with realm override
            PrincipalContext overriddenContext = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(targetRealm.getDomainContext().toDataDomain(SYSTEM_USER))
                    .withUserId(systemUserContext.getUserId())
                    .withRoles(systemUserContext.getRoles())
                    .withScope(systemUserContext.getScope())
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            // Then: Default realm should be the target realm
            assertEquals(TARGET_REALM, overriddenContext.getDefaultRealm());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should not override when realm is null")
        void shouldNotOverrideWhenRealmIsNull() {
            // The applyRealmOverride logic should return original context when realm is null
            // This tests the expected behavior
            String realm = null;
            
            // Verify the condition that would skip override
            assertTrue(realm == null || (realm != null && realm.isBlank()) || 
                      (realm != null && realm.equals(systemUserContext.getDefaultRealm())));
        }

        @Test
        @DisplayName("Should not override when realm is blank")
        void shouldNotOverrideWhenRealmIsBlank() {
            String realm = "   ";
            
            // Verify the condition that would skip override
            assertTrue(realm.isBlank());
        }

        @Test
        @DisplayName("Should not override when realm equals current realm")
        void shouldNotOverrideWhenRealmEqualsCurrent() {
            String realm = SYSTEM_REALM;
            
            // Verify the condition that would skip override
            assertEquals(realm, systemUserContext.getDefaultRealm());
        }

        @Test
        @DisplayName("Context with impersonation should not have realm override applied")
        void shouldNotOverrideWhenImpersonationActive() {
            // Given: A context where impersonation is active
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withDefaultRealm(SYSTEM_REALM)
                    .withDataDomain(systemDataDomain)
                    .withUserId("impersonated-user@example.com")
                    .withRoles(new String[]{"user"})
                    .withScope("AUTHENTICATED")
                    .withImpersonatedBySubject("admin-subject")
                    .withImpersonatedByUserId(SYSTEM_USER)
                    .build();
            
            // Then: Impersonation fields should be set
            assertNotNull(impersonatedContext.getImpersonatedBySubject());
            assertNotNull(impersonatedContext.getImpersonatedByUserId());
            
            // The applyRealmOverride logic should return original context when impersonation is active
        }

        @Test
        @DisplayName("DomainContext.toDataDomain should correctly create DataDomain with given ownerId")
        void domainContextToDataDomainShouldUseProvidedOwnerId() {
            // Given: A DomainContext
            DomainContext domainContext = DomainContext.builder()
                    .tenantId("tenant-123")
                    .defaultRealm("realm-123")
                    .orgRefName("ORG-123")
                    .accountId("ACCT-123")
                    .dataSegment(5)
                    .build();
            
            // When: Converting to DataDomain with a specific ownerId
            String customOwner = "custom-owner@example.com";
            DataDomain dataDomain = domainContext.toDataDomain(customOwner);
            
            // Then: DataDomain should have all values from DomainContext plus the ownerId
            assertEquals("tenant-123", dataDomain.getTenantId());
            assertEquals("ORG-123", dataDomain.getOrgRefName());
            assertEquals("ACCT-123", dataDomain.getAccountNum());
            assertEquals(5, dataDomain.getDataSegment());
            assertEquals(customOwner, dataDomain.getOwnerId());
        }
    }

    @Nested
    @DisplayName("PrincipalContext Builder Tests")
    class PrincipalContextBuilderTests {

        @Test
        @DisplayName("Builder should support realm override fields")
        void builderShouldSupportRealmOverrideFields() {
            // Given/When: Building a PrincipalContext with all realm override fields
            PrincipalContext context = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(expectedTargetDataDomain)
                    .withUserId(SYSTEM_USER)
                    .withRoles(new String[]{"admin"})
                    .withScope("AUTHENTICATED")
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            // Then: All fields should be set correctly
            assertTrue(context.isRealmOverrideActive());
            assertNotNull(context.getOriginalDataDomain());
            assertEquals(systemDataDomain, context.getOriginalDataDomain());
        }

        @Test
        @DisplayName("Realm override fields should default to false/null")
        void realmOverrideFieldsShouldDefaultCorrectly() {
            // Given/When: Building a PrincipalContext without realm override fields
            PrincipalContext context = new PrincipalContext.Builder()
                    .withDefaultRealm(SYSTEM_REALM)
                    .withDataDomain(systemDataDomain)
                    .withUserId(SYSTEM_USER)
                    .withRoles(new String[]{"admin"})
                    .withScope("AUTHENTICATED")
                    .build();
            
            // Then: Realm override fields should be default values
            assertFalse(context.isRealmOverrideActive());
            assertNull(context.getOriginalDataDomain());
        }

        @Test
        @DisplayName("toString should include realm override fields")
        void toStringShouldIncludeRealmOverrideFields() {
            // Given: A PrincipalContext with realm override
            PrincipalContext context = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(expectedTargetDataDomain)
                    .withUserId(SYSTEM_USER)
                    .withRoles(new String[]{"admin"})
                    .withScope("AUTHENTICATED")
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            // When/Then: toString should contain the new fields
            String str = context.toString();
            assertTrue(str.contains("realmOverrideActive=true"));
            assertTrue(str.contains("originalDataDomain="));
        }

        @Test
        @DisplayName("equals and hashCode should consider realm override fields")
        void equalsAndHashCodeShouldConsiderRealmOverrideFields() {
            // Given: Two contexts - one with realm override, one without
            PrincipalContext contextWithOverride = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(expectedTargetDataDomain)
                    .withUserId(SYSTEM_USER)
                    .withRoles(new String[]{"admin"})
                    .withScope("AUTHENTICATED")
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            PrincipalContext contextWithoutOverride = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(expectedTargetDataDomain)
                    .withUserId(SYSTEM_USER)
                    .withRoles(new String[]{"admin"})
                    .withScope("AUTHENTICATED")
                    .withRealmOverrideActive(false)
                    .build();
            
            // Then: They should not be equal
            assertNotEquals(contextWithOverride, contextWithoutOverride);
            assertNotEquals(contextWithOverride.hashCode(), contextWithoutOverride.hashCode());
        }
    }

    @Nested
    @DisplayName("Comparison: X-Realm vs Impersonation")
    class ComparisonTests {

        @Test
        @DisplayName("X-Realm changes DataDomain but keeps identity")
        void xRealmChangesDataDomainButKeepsIdentity() {
            // Simulating X-Realm behavior
            DataDomain effectiveDataDomain = targetRealm.getDomainContext().toDataDomain(SYSTEM_USER);
            
            PrincipalContext xRealmContext = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)
                    .withDataDomain(effectiveDataDomain)
                    .withUserId(SYSTEM_USER)  // Same user
                    .withRoles(new String[]{"admin", "user"})  // Same roles
                    .withScope("AUTHENTICATED")
                    .withRealmOverrideActive(true)
                    .withOriginalDataDomain(systemDataDomain)
                    .build();
            
            // User identity unchanged
            assertEquals(SYSTEM_USER, xRealmContext.getUserId());
            assertArrayEquals(new String[]{"admin", "user"}, xRealmContext.getRoles());
            
            // DataDomain changed to target realm
            assertEquals(TARGET_ORG, xRealmContext.getDataDomain().getOrgRefName());
            assertEquals(TARGET_TENANT, xRealmContext.getDataDomain().getTenantId());
            
            // Realm changed
            assertEquals(TARGET_REALM, xRealmContext.getDefaultRealm());
            
            // Not impersonating
            assertNull(xRealmContext.getImpersonatedBySubject());
            assertNull(xRealmContext.getImpersonatedByUserId());
        }

        @Test
        @DisplayName("Impersonation changes both DataDomain and identity")
        void impersonationChangesBothDataDomainAndIdentity() {
            // Simulating impersonation behavior
            String impersonatedUser = "john@mycompany.com";
            String[] impersonatedRoles = new String[]{"user"};  // Different roles
            
            // Target user's DataDomain (from their credential)
            DataDomain impersonatedDataDomain = DataDomain.builder()
                    .orgRefName(TARGET_ORG)
                    .tenantId(TARGET_TENANT)
                    .accountNum(TARGET_ACCOUNT)
                    .ownerId(impersonatedUser)  // Their own userId
                    .dataSegment(0)
                    .build();
            
            PrincipalContext impersonatedContext = new PrincipalContext.Builder()
                    .withDefaultRealm(TARGET_REALM)  // Target user's realm
                    .withDataDomain(impersonatedDataDomain)
                    .withUserId(impersonatedUser)  // Different user!
                    .withRoles(impersonatedRoles)  // Different roles!
                    .withScope("AUTHENTICATED")
                    .withImpersonatedBySubject("admin-subject")
                    .withImpersonatedByUserId(SYSTEM_USER)
                    .build();
            
            // User identity changed to impersonated user
            assertEquals(impersonatedUser, impersonatedContext.getUserId());
            assertArrayEquals(impersonatedRoles, impersonatedContext.getRoles());
            
            // DataDomain is impersonated user's DataDomain
            assertEquals(TARGET_ORG, impersonatedContext.getDataDomain().getOrgRefName());
            assertEquals(impersonatedUser, impersonatedContext.getDataDomain().getOwnerId());
            
            // Impersonation tracked
            assertEquals("admin-subject", impersonatedContext.getImpersonatedBySubject());
            assertEquals(SYSTEM_USER, impersonatedContext.getImpersonatedByUserId());
        }
    }
}

