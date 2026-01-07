package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the defaultRealm variable availability in permission filters.
 * This is critical for X-Realm functionality where filters need to dynamically
 * scope by the current realm.
 */
public class DefaultRealmVariableTest {

    // Minimal dummy model just to satisfy modelClass parameter
    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }
        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    private PrincipalContext principalWithRealm(String realm) {
        return new PrincipalContext.Builder()
                .withUserId("user-1")
                .withDefaultRealm(realm)
                .withDataDomain(DataDomain.builder()
                        .orgRefName("end2endlogic")
                        .accountNum("0000000001")
                        .tenantId("tenant-1")
                        .dataSegment(0)
                        .ownerId("owner-1")
                        .build())
                .build();
    }

    private ResourceContext resource() {
        return new ResourceContext.Builder()
                .withArea("sales")
                .withFunctionalDomain("order")
                .withAction("view")
                .withResourceId("res-1")
                .build();
    }

    @Nested
    @DisplayName("defaultRealm variable in standard variable map")
    class StandardVariableMapTests {

        @Test
        @DisplayName("defaultRealm is included in standard variable map")
        void defaultRealmIsIncludedInVariableMap() {
            PrincipalContext pc = principalWithRealm("mycompany-realm");
            ResourceContext rc = resource();

            Map<String, String> vars = MorphiaUtils.createStandardVariableMapFrom(pc, rc);

            assertTrue(vars.containsKey("defaultRealm"), "defaultRealm should be in variable map");
            assertEquals("mycompany-realm", vars.get("defaultRealm"));
        }

        @Test
        @DisplayName("defaultRealm changes when realm changes")
        void defaultRealmChangesWithRealm() {
            PrincipalContext pc1 = principalWithRealm("realm-a");
            PrincipalContext pc2 = principalWithRealm("realm-b");
            ResourceContext rc = resource();

            Map<String, String> vars1 = MorphiaUtils.createStandardVariableMapFrom(pc1, rc);
            Map<String, String> vars2 = MorphiaUtils.createStandardVariableMapFrom(pc2, rc);

            assertEquals("realm-a", vars1.get("defaultRealm"));
            assertEquals("realm-b", vars2.get("defaultRealm"));
            assertNotEquals(vars1.get("defaultRealm"), vars2.get("defaultRealm"));
        }
    }

    @Nested
    @DisplayName("defaultRealm variable in filter strings")
    class FilterStringTests {

        @Test
        @DisplayName("filter can use ${defaultRealm} variable")
        void filterCanUseDefaultRealmVariable() {
            String query = "realm:${defaultRealm}";
            PrincipalContext pc = principalWithRealm("test-realm-xyz");
            ResourceContext rc = resource();
            MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(pc, rc, null);

            Filter f = MorphiaUtils.convertToFilter(query, vars, DummyModel.class);

            assertEquals("$eq", f.getName());
            assertEquals("realm", f.getField());
            assertEquals("test-realm-xyz", f.getValue());
        }

        @Test
        @DisplayName("X-Realm scenario: filter scopes to target realm")
        void xRealmScenarioFilterScopesToTargetRealm() {
            // Simulate X-Realm: user's original realm is "system-com", 
            // but they switched to "mycompany-xyz" realm
            String query = "dataDomain.tenantId:${defaultRealm}";

            // After X-Realm override, PrincipalContext has the target realm
            PrincipalContext pcAfterRealmSwitch = principalWithRealm("mycompany-xyz");
            ResourceContext rc = resource();
            MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(pcAfterRealmSwitch, rc, null);

            Filter f = MorphiaUtils.convertToFilter(query, vars, DummyModel.class);

            assertEquals("$eq", f.getName());
            assertEquals("dataDomain.tenantId", f.getField());
            assertEquals("mycompany-xyz", f.getValue(), 
                    "Filter should scope to the switched realm, not the original");
        }

        @Test
        @DisplayName("filter can combine defaultRealm with other variables")
        void filterCanCombineDefaultRealmWithOtherVars() {
            String query = "realm:${defaultRealm}&&ownerId:${ownerId}";
            PrincipalContext pc = principalWithRealm("combined-realm");
            ResourceContext rc = resource();
            MorphiaUtils.VariableBundle vars = MorphiaUtils.buildVariableBundle(pc, rc, null);

            Filter f = MorphiaUtils.convertToFilter(query, vars, DummyModel.class);

            // Should produce an AND filter with both conditions
            assertEquals("$and", f.getName());
            String filterStr = f.toString();
            assertTrue(filterStr.contains("combined-realm"), "Filter should contain resolved realm");
            assertTrue(filterStr.contains("owner-1"), "Filter should contain resolved ownerId");
        }
    }

    @Nested
    @DisplayName("VariableBundle includes defaultRealm")
    class VariableBundleTests {

        @Test
        @DisplayName("VariableBundle strings map contains defaultRealm")
        void variableBundleStringsContainsDefaultRealm() {
            PrincipalContext pc = principalWithRealm("bundle-test-realm");
            ResourceContext rc = resource();

            MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pc, rc, null);

            assertTrue(bundle.strings.containsKey("defaultRealm"));
            assertEquals("bundle-test-realm", bundle.strings.get("defaultRealm"));
        }

        @Test
        @DisplayName("VariableBundle objects map contains defaultRealm")
        void variableBundleObjectsContainsDefaultRealm() {
            PrincipalContext pc = principalWithRealm("bundle-test-realm");
            ResourceContext rc = resource();

            MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pc, rc, null);

            assertTrue(bundle.objects.containsKey("defaultRealm"));
            assertEquals("bundle-test-realm", bundle.objects.get("defaultRealm"));
        }

        @Test
        @DisplayName("extra objects don't override defaultRealm")
        void extraObjectsDontOverrideDefaultRealm() {
            PrincipalContext pc = principalWithRealm("original-realm");
            ResourceContext rc = resource();
            Map<String, Object> extra = new HashMap<>();
            extra.put("customVar", "custom-value");

            MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pc, rc, extra);

            // defaultRealm should still be from principal context
            assertEquals("original-realm", bundle.strings.get("defaultRealm"));
            // Extra vars should be present
            assertEquals("custom-value", bundle.strings.get("customVar"));
        }
    }
}

