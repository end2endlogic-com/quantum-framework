package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PrincipalContext custom properties feature.
 */
class PrincipalContextCustomPropertiesTest {

    private DataDomain createTestDataDomain() {
        DataDomain dd = new DataDomain();
        dd.setTenantId("test-tenant");
        dd.setOrgRefName("test-org");
        dd.setAccountNum("test-account");
        dd.setOwnerId("test-user");
        dd.setDataSegment(0);
        return dd;
    }

    @Test
    void testCustomPropertiesViaBuilder() {
        Map<String, Object> props = new HashMap<>();
        props.put("associateId", "ASSOC-123");
        props.put("region", "US-EAST");

        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperties(props)
                .build();

        assertEquals("ASSOC-123", context.getCustomProperty("associateId"));
        assertEquals("US-EAST", context.getCustomProperty("region"));
        assertNull(context.getCustomProperty("nonexistent"));
    }

    @Test
    void testCustomPropertyWithType() {
        Set<String> locationIds = new HashSet<>(Arrays.asList("LOC-1", "LOC-2", "LOC-3"));

        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperty("accessibleLocationIds", locationIds)
                .build();

        // Get as raw object
        Object raw = context.getCustomProperty("accessibleLocationIds");
        assertNotNull(raw);
        assertTrue(raw instanceof Set);

        // Get with type casting
        @SuppressWarnings("unchecked")
        Set<String> retrieved = context.getCustomProperty("accessibleLocationIds", Set.class);
        assertNotNull(retrieved);
        assertEquals(3, retrieved.size());
        assertTrue(retrieved.contains("LOC-1"));
    }

    @Test
    void testCustomPropertiesAreImmutable() {
        Map<String, Object> props = new HashMap<>();
        props.put("key1", "value1");

        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperties(props)
                .build();

        // Verify the returned map is unmodifiable
        Map<String, Object> retrievedProps = context.getCustomProperties();
        assertThrows(UnsupportedOperationException.class, () -> {
            retrievedProps.put("newKey", "newValue");
        });
    }

    @Test
    void testSingleCustomProperty() {
        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperty("territoryId", "TERR-456")
                .withCustomProperty("salesLevel", 3)
                .build();

        assertEquals("TERR-456", context.getCustomProperty("territoryId"));
        assertEquals(3, context.getCustomProperty("salesLevel"));
    }

    @Test
    void testEmptyCustomProperties() {
        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .build();

        assertNotNull(context.getCustomProperties());
        assertTrue(context.getCustomProperties().isEmpty());
    }

    @Test
    void testCustomPropertiesInEqualsAndHashCode() {
        Map<String, Object> props1 = new HashMap<>();
        props1.put("key", "value");

        Map<String, Object> props2 = new HashMap<>();
        props2.put("key", "value");

        Map<String, Object> props3 = new HashMap<>();
        props3.put("key", "different");

        PrincipalContext context1 = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperties(props1)
                .build();

        PrincipalContext context2 = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperties(props2)
                .build();

        PrincipalContext context3 = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperties(props3)
                .build();

        // context1 and context2 should be equal (same custom properties)
        assertEquals(context1, context2);
        assertEquals(context1.hashCode(), context2.hashCode());

        // context1 and context3 should not be equal (different custom properties)
        assertNotEquals(context1, context3);
    }

    @Test
    void testCustomPropertiesInToString() {
        PrincipalContext context = new PrincipalContext.Builder()
                .withDefaultRealm("test-realm")
                .withDataDomain(createTestDataDomain())
                .withUserId("test-user")
                .withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED")
                .withCustomProperty("testProp", "testValue")
                .build();

        String str = context.toString();
        assertTrue(str.contains("customProperties"));
        assertTrue(str.contains("testProp"));
    }
}
