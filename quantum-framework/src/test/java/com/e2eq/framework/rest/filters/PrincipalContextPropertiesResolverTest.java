package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.PrincipalContextPropertiesResolver;
import com.e2eq.framework.model.securityrules.ResolutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PrincipalContextPropertiesResolver SPI.
 *
 * <p>This tests the resolver behavior including:
 * - Resolution context providing correct data
 * - Multiple resolvers executing in priority order
 * - Custom properties being added to PrincipalContext
 * - Error handling when resolvers fail
 */
class PrincipalContextPropertiesResolverTest {

    private DataDomain testDataDomain;

    @BeforeEach
    void setUp() {
        // Create test data domain
        testDataDomain = new DataDomain();
        testDataDomain.setTenantId("test-tenant");
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("test-account");
        testDataDomain.setOwnerId("test-user");
        testDataDomain.setDataSegment(0);
    }

    @Test
    void testResolutionContextProvidesCorrectData() {
        // Create resolution context using the stub implementation
        ResolutionContext resolutionContext = new StubResolutionContext(
            false, // not anonymous
            "test-user",
            Set.of("user", "admin"),
            "test-user",
            "user-subject-123",
            "test-realm",
            testDataDomain,
            null,
            Map.of("X-Custom-Header", "custom-value"),
            Map.of("sub", "user-subject-123", "email", "test@example.com"),
            false
        );

        // Verify all context values are accessible
        assertFalse(resolutionContext.isAnonymous());
        assertEquals("test-user", resolutionContext.getPrincipalName());
        assertEquals(Set.of("user", "admin"), resolutionContext.getIdentityRoles());
        assertEquals("test-user", resolutionContext.getUserId());
        assertEquals("test-realm", resolutionContext.getRealm());
        assertEquals(testDataDomain, resolutionContext.getDataDomain());

        // Verify JWT claim access
        assertEquals("user-subject-123", resolutionContext.getJwtClaim("sub"));
        assertEquals("test@example.com", resolutionContext.getJwtClaim("email"));

        // Verify header access
        assertEquals("custom-value", resolutionContext.getHeader("X-Custom-Header"));

        // Verify subject ID from JWT
        assertTrue(resolutionContext.getSubjectId().isPresent());
        assertEquals("user-subject-123", resolutionContext.getSubjectId().get());
    }

    @Test
    void testResolutionContextWithCredentials() {
        // Create test DomainContext for the credential
        DomainContext credDomainContext = DomainContext.builder()
            .tenantId("test-tenant")
            .defaultRealm("test-realm")
            .orgRefName("test-org")
            .accountId("test-account")
            .build();

        CredentialUserIdPassword credentials = CredentialUserIdPassword.builder()
            .userId("cred-user")
            .subject("cred-subject")
            .roles(new String[]{"role1", "role2"})
            .domainContext(credDomainContext)
            .lastUpdate(new Date())
            .build();

        ResolutionContext resolutionContext = new StubResolutionContext(
            false,
            "test-user",
            Set.of("user", "admin"),
            "test-user",
            null, // no JWT subject
            "test-realm",
            testDataDomain,
            null,
            Map.of(),
            Map.of(),
            true, // has credentials
            credentials
        );

        // Verify credential access
        assertTrue(resolutionContext.hasCredentials());
        assertTrue(resolutionContext.getCredentialUserId().isPresent());
        assertEquals("cred-user", resolutionContext.getCredentialUserId().get());
        assertTrue(resolutionContext.getCredentialSubject().isPresent());
        assertEquals("cred-subject", resolutionContext.getCredentialSubject().get());
        assertArrayEquals(new String[]{"role1", "role2"}, resolutionContext.getCredentialRoles());

        // Without JWT, subject ID should come from credentials
        assertTrue(resolutionContext.getSubjectId().isPresent());
        assertEquals("cred-subject", resolutionContext.getSubjectId().get());
    }

    @Test
    void testResolutionContextAnonymous() {
        ResolutionContext resolutionContext = new StubResolutionContext(
            true, // anonymous
            null,
            Set.of(),
            "anonymous",
            null,
            "test-realm",
            testDataDomain,
            null,
            Map.of(),
            Map.of(),
            false
        );

        assertTrue(resolutionContext.isAnonymous());
        assertNull(resolutionContext.getPrincipalName());
        assertFalse(resolutionContext.hasCredentials());
    }

    @Test
    void testSimplePropertyResolver() {
        // Create a simple resolver
        PrincipalContextPropertiesResolver resolver = context -> {
            Map<String, Object> props = new HashMap<>();
            props.put("associateId", "ASSOC-123");
            props.put("region", "US-EAST");
            return props;
        };

        ResolutionContext resolutionContext = createDefaultContext();

        Map<String, Object> properties = resolver.resolve(resolutionContext);

        assertEquals(2, properties.size());
        assertEquals("ASSOC-123", properties.get("associateId"));
        assertEquals("US-EAST", properties.get("region"));
    }

    @Test
    void testResolverWithCollectionProperty() {
        // Create a resolver that returns a collection
        PrincipalContextPropertiesResolver resolver = context -> {
            Map<String, Object> props = new HashMap<>();
            Set<String> locationIds = new HashSet<>(Arrays.asList("LOC-1", "LOC-2", "LOC-3"));
            props.put("accessibleLocationIds", locationIds);
            return props;
        };

        ResolutionContext resolutionContext = createDefaultContext();

        Map<String, Object> properties = resolver.resolve(resolutionContext);

        assertTrue(properties.containsKey("accessibleLocationIds"));
        @SuppressWarnings("unchecked")
        Set<String> locationIds = (Set<String>) properties.get("accessibleLocationIds");
        assertEquals(3, locationIds.size());
        assertTrue(locationIds.contains("LOC-1"));
        assertTrue(locationIds.contains("LOC-2"));
        assertTrue(locationIds.contains("LOC-3"));
    }

    @Test
    void testResolverPriorityOrdering() {
        // Create resolvers with different priorities
        List<String> executionOrder = new ArrayList<>();

        PrincipalContextPropertiesResolver lowPriority = new PrincipalContextPropertiesResolver() {
            @Override
            public int priority() {
                return 100;
            }

            @Override
            public Map<String, Object> resolve(ResolutionContext context) {
                executionOrder.add("low");
                return Map.of("fromLow", "value1");
            }
        };

        PrincipalContextPropertiesResolver highPriority = new PrincipalContextPropertiesResolver() {
            @Override
            public int priority() {
                return 500;
            }

            @Override
            public Map<String, Object> resolve(ResolutionContext context) {
                executionOrder.add("high");
                return Map.of("fromHigh", "value2");
            }
        };

        PrincipalContextPropertiesResolver defaultPriority = new PrincipalContextPropertiesResolver() {
            // default priority is 1000

            @Override
            public Map<String, Object> resolve(ResolutionContext context) {
                executionOrder.add("default");
                return Map.of("fromDefault", "value3");
            }
        };

        // Sort resolvers by priority (simulating what SecurityFilter does)
        List<PrincipalContextPropertiesResolver> resolvers = new ArrayList<>(Arrays.asList(
            highPriority, defaultPriority, lowPriority
        ));
        resolvers.sort(Comparator.comparingInt(PrincipalContextPropertiesResolver::priority));

        ResolutionContext resolutionContext = createDefaultContext();

        // Execute in sorted order
        Map<String, Object> allProperties = new LinkedHashMap<>();
        for (PrincipalContextPropertiesResolver resolver : resolvers) {
            allProperties.putAll(resolver.resolve(resolutionContext));
        }

        // Verify execution order (lowest priority value first)
        assertEquals(Arrays.asList("low", "high", "default"), executionOrder);

        // Verify all properties collected
        assertEquals(3, allProperties.size());
        assertEquals("value1", allProperties.get("fromLow"));
        assertEquals("value2", allProperties.get("fromHigh"));
        assertEquals("value3", allProperties.get("fromDefault"));
    }

    @Test
    void testResolverOverwritesProperties() {
        // Test that later resolvers can overwrite properties from earlier ones
        PrincipalContextPropertiesResolver first = new PrincipalContextPropertiesResolver() {
            @Override
            public int priority() {
                return 100;
            }

            @Override
            public Map<String, Object> resolve(ResolutionContext context) {
                Map<String, Object> props = new HashMap<>();
                props.put("sharedKey", "firstValue");
                props.put("uniqueFirst", "value1");
                return props;
            }
        };

        PrincipalContextPropertiesResolver second = new PrincipalContextPropertiesResolver() {
            @Override
            public int priority() {
                return 200;
            }

            @Override
            public Map<String, Object> resolve(ResolutionContext context) {
                Map<String, Object> props = new HashMap<>();
                props.put("sharedKey", "secondValue");
                props.put("uniqueSecond", "value2");
                return props;
            }
        };

        List<PrincipalContextPropertiesResolver> resolvers = new ArrayList<>(Arrays.asList(first, second));
        resolvers.sort(Comparator.comparingInt(PrincipalContextPropertiesResolver::priority));

        ResolutionContext resolutionContext = createDefaultContext();

        Map<String, Object> allProperties = new LinkedHashMap<>();
        for (PrincipalContextPropertiesResolver resolver : resolvers) {
            allProperties.putAll(resolver.resolve(resolutionContext));
        }

        // The second resolver's value should overwrite the first
        assertEquals("secondValue", allProperties.get("sharedKey"));
        assertEquals("value1", allProperties.get("uniqueFirst"));
        assertEquals("value2", allProperties.get("uniqueSecond"));
    }

    @Test
    void testResolverReturnsEmptyMap() {
        PrincipalContextPropertiesResolver resolver = context -> Collections.emptyMap();

        ResolutionContext resolutionContext = createDefaultContext();

        Map<String, Object> properties = resolver.resolve(resolutionContext);

        assertNotNull(properties);
        assertTrue(properties.isEmpty());
    }

    @Test
    void testResolverSkipsAnonymousUser() {
        PrincipalContextPropertiesResolver resolver = context -> {
            if (context.isAnonymous()) {
                return Collections.emptyMap();
            }
            return Map.of("associateId", "ASSOC-123");
        };

        ResolutionContext resolutionContext = new StubResolutionContext(
            true, // anonymous
            null,
            Set.of(),
            "anonymous",
            null,
            "test-realm",
            testDataDomain,
            null,
            Map.of(),
            Map.of(),
            false
        );

        Map<String, Object> properties = resolver.resolve(resolutionContext);

        assertTrue(properties.isEmpty());
    }

    @Test
    void testCustomPropertiesIntegratedWithPrincipalContext() {
        // Simulate the full flow: resolver returns properties, they get added to PrincipalContext
        PrincipalContextPropertiesResolver resolver = context -> {
            Map<String, Object> props = new HashMap<>();
            props.put("associateId", "ASSOC-456");
            props.put("accessibleTerritoryIds", Set.of("TERR-1", "TERR-2"));
            return props;
        };

        ResolutionContext resolutionContext = createDefaultContext();

        Map<String, Object> resolvedProperties = resolver.resolve(resolutionContext);

        // Build PrincipalContext with custom properties (simulating SecurityFilter behavior)
        PrincipalContext principalContext = new PrincipalContext.Builder()
            .withDefaultRealm("test-realm")
            .withDataDomain(testDataDomain)
            .withUserId("test-user")
            .withRoles(new String[]{"user", "admin"})
            .withScope("AUTHENTICATED")
            .withCustomProperties(resolvedProperties)
            .build();

        // Verify properties are accessible
        assertEquals("ASSOC-456", principalContext.getCustomProperty("associateId"));

        @SuppressWarnings("unchecked")
        Set<String> territoryIds = principalContext.getCustomProperty("accessibleTerritoryIds", Set.class);
        assertNotNull(territoryIds);
        assertEquals(2, territoryIds.size());
        assertTrue(territoryIds.contains("TERR-1"));
        assertTrue(territoryIds.contains("TERR-2"));
    }

    @Test
    void testResolverUsesJwtClaims() {
        PrincipalContextPropertiesResolver resolver = context -> {
            Map<String, Object> props = new HashMap<>();
            Object customClaim = context.getJwtClaim("custom_claim");
            if (customClaim != null) {
                props.put("customClaimValue", customClaim.toString());
            }
            Object tenantClaim = context.getJwtClaim("tenant_id");
            if (tenantClaim != null) {
                props.put("jwtTenantId", tenantClaim.toString());
            }
            return props;
        };

        ResolutionContext resolutionContext = new StubResolutionContext(
            false,
            "test-user",
            Set.of("user"),
            "test-user",
            "user-subject",
            "test-realm",
            testDataDomain,
            null,
            Map.of(),
            Map.of("custom_claim", "claim_value", "tenant_id", "jwt-tenant"),
            false
        );

        Map<String, Object> properties = resolver.resolve(resolutionContext);

        assertEquals("claim_value", properties.get("customClaimValue"));
        assertEquals("jwt-tenant", properties.get("jwtTenantId"));
    }

    @Test
    void testResolverUsesHttpHeaders() {
        PrincipalContextPropertiesResolver resolver = context -> {
            Map<String, Object> props = new HashMap<>();
            String region = context.getHeader("X-Region");
            if (region != null) {
                props.put("requestRegion", region);
            }
            String featureFlag = context.getHeader("X-Feature-Flag");
            if ("enabled".equals(featureFlag)) {
                props.put("newFeatureEnabled", true);
            }
            return props;
        };

        ResolutionContext resolutionContext = new StubResolutionContext(
            false,
            "test-user",
            Set.of("user"),
            "test-user",
            "user-subject",
            "test-realm",
            testDataDomain,
            null,
            Map.of("X-Region", "US-WEST", "X-Feature-Flag", "enabled"),
            Map.of(),
            false
        );

        Map<String, Object> properties = resolver.resolve(resolutionContext);

        assertEquals("US-WEST", properties.get("requestRegion"));
        assertEquals(true, properties.get("newFeatureEnabled"));
    }

    @Test
    void testDefaultPriorityValue() {
        PrincipalContextPropertiesResolver resolver = context -> Collections.emptyMap();

        // Default priority should be 1000
        assertEquals(1000, resolver.priority());
    }

    @Test
    void testResolverWithNullSecurityIdentity() {
        // Test graceful handling when security identity is null (anonymous)
        ResolutionContext resolutionContext = new StubResolutionContext(
            true,
            null,
            Set.of(),
            "anonymous",
            null,
            "test-realm",
            testDataDomain,
            null,
            Map.of(),
            Map.of(),
            false
        );

        assertTrue(resolutionContext.isAnonymous());
        assertNull(resolutionContext.getPrincipalName());
        assertEquals(Set.of(), resolutionContext.getIdentityRoles());
    }

    private ResolutionContext createDefaultContext() {
        return new StubResolutionContext(
            false,
            "test-user",
            Set.of("user", "admin"),
            "test-user",
            "user-subject-123",
            "test-realm",
            testDataDomain,
            null,
            Map.of("X-Custom-Header", "custom-value"),
            Map.of("sub", "user-subject-123", "email", "test@example.com"),
            false
        );
    }

    /**
     * Stub implementation of ResolutionContext for testing without mocks.
     */
    private static class StubResolutionContext implements ResolutionContext {
        private final boolean anonymous;
        private final String principalName;
        private final Set<String> identityRoles;
        private final String userId;
        private final String subjectId;
        private final String realm;
        private final DataDomain dataDomain;
        private final DomainContext domainContext;
        private final Map<String, String> headers;
        private final Map<String, Object> jwtClaims;
        private final boolean hasCredentials;
        private final CredentialUserIdPassword credentials;

        StubResolutionContext(boolean anonymous, String principalName, Set<String> identityRoles,
                             String userId, String subjectId, String realm, DataDomain dataDomain,
                             DomainContext domainContext, Map<String, String> headers,
                             Map<String, Object> jwtClaims, boolean hasCredentials) {
            this(anonymous, principalName, identityRoles, userId, subjectId, realm, dataDomain,
                 domainContext, headers, jwtClaims, hasCredentials, null);
        }

        StubResolutionContext(boolean anonymous, String principalName, Set<String> identityRoles,
                             String userId, String subjectId, String realm, DataDomain dataDomain,
                             DomainContext domainContext, Map<String, String> headers,
                             Map<String, Object> jwtClaims, boolean hasCredentials,
                             CredentialUserIdPassword credentials) {
            this.anonymous = anonymous;
            this.principalName = principalName;
            this.identityRoles = identityRoles;
            this.userId = userId;
            this.subjectId = subjectId;
            this.realm = realm;
            this.dataDomain = dataDomain;
            this.domainContext = domainContext;
            this.headers = headers;
            this.jwtClaims = jwtClaims;
            this.hasCredentials = hasCredentials;
            this.credentials = credentials;
        }

        @Override
        public boolean isAnonymous() {
            return anonymous;
        }

        @Override
        public String getPrincipalName() {
            return principalName;
        }

        @Override
        public Set<String> getIdentityRoles() {
            return identityRoles;
        }

        @Override
        public String getUserId() {
            return userId;
        }

        @Override
        public Optional<String> getSubjectId() {
            if (subjectId != null) {
                return Optional.of(subjectId);
            }
            if (credentials != null && credentials.getSubject() != null) {
                return Optional.of(credentials.getSubject());
            }
            return Optional.empty();
        }

        @Override
        public String getRealm() {
            return realm;
        }

        @Override
        public DataDomain getDataDomain() {
            return dataDomain;
        }

        @Override
        public Optional<DomainContext> getDomainContext() {
            return Optional.ofNullable(domainContext);
        }

        @Override
        public String getHeader(String name) {
            return headers.get(name);
        }

        @Override
        public Object getJwtClaim(String claimName) {
            return jwtClaims.get(claimName);
        }

        @Override
        public boolean hasCredentials() {
            return hasCredentials;
        }

        @Override
        public Optional<String> getCredentialUserId() {
            return credentials != null ? Optional.ofNullable(credentials.getUserId()) : Optional.empty();
        }

        @Override
        public Optional<String> getCredentialSubject() {
            return credentials != null ? Optional.ofNullable(credentials.getSubject()) : Optional.empty();
        }

        @Override
        public String[] getCredentialRoles() {
            return credentials != null ? credentials.getRoles() : new String[0];
        }
    }
}
