package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class QueryToFilterListenerElemMatchTest {

    // Reuse your DummyModel, principal(), resource() helpers if they live elsewhere
    public static class DummyModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() {
            return "test-area";
        }

        @Override
        public String bmFunctionalDomain() {
            return "test-domain";
        }
    }

    private PrincipalContext principal() {
        return new PrincipalContext.Builder()
                .withUserId("user-1")
                .withDefaultRealm("test-realm")
                .withDataDomain(new com.e2eq.framework.model.persistent.base.DataDomain(
                        "end2endlogic", "0000000001", "tenant-1", 0, "owner-1"))
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

    // 1) Single elemMatch with two inner predicates (your original core case)
    @Test
    public void elemMatch_twoInnerPredicates_AND() {
        String q =
                "dynamicAttributeSets.attributes:{"
                        + "id:21f63b90-08b4-4280-a28d-f003f9c114b3&&"
                        + "value:\"Conference hall\""
                        + "}";

        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        // Top-level should be $elemMatch on the field
        assertEquals("$elemMatch", f.getName());
        assertEquals("dynamicAttributeSets.attributes", f.getField());

        // We can validate structure via toString(); expect an inner $and with both clauses
        String s = f.toString();
        assertTrue(s.contains("$elemMatch"), s);
        // inner AND (exact key depends on Filters impl but usually "$and")
        assertTrue(s.contains("$and") || s.contains("and"), s);
        assertTrue(s.contains("id"), s);
        assertTrue(s.contains("21f63b90-08b4-4280-a28d-f003f9c114b3"), s);
        assertTrue(s.contains("value"), s);
        assertTrue(s.contains("Conference hall"), s);
    }

    // 2) Two sibling elemMatch joined by &&
    @Test
    public void twoSibling_elemMatch_joinedBy_AND() {
        String q =
                "dynamicAttributeSets.attributes:{id:21f63b90-08b4-4280-a28d-f003f9c114b3&&value:\"Conference hall\"}"
                        + "&&"
                        + "dynamicAttributeSets.attributes:{id:94138c39-2115-4681-8c18-aa0c0596b065&&value:#99}";
        Log.info(q);

        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        // Should be a top-level $and combining two $elemMatch filters
        assertEquals("$and", f.getName());
        String s = f.toString();
        assertTrue(s.contains("$elemMatch"), s);
        assertTrue(s.contains("dynamicAttributeSets.attributes"), s);

        // Both inner elemMatch blocks present
        assertTrue(s.contains("Conference hall"), s);
        assertTrue(s.contains("99"), s);
    }

    // 3) elemMatch not first (preceded by a simple predicate)
    @Test
    public void elemMatch_notFirst_precededBy_simplePredicate() {
        String q =
                "address.city:Raleigh&&"
                        + "dynamicAttributeSets.attributes:{id:21f63b90-08b4-4280-a28d-f003f9c114b3&&value:\"Conference hall\"}";

        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        // top-level AND of eq(city,"Raleigh") and an elemMatch
        assertEquals("$and", f.getName());
        String s = f.toString();
        assertTrue(s.contains("address.city"), s);
        assertTrue(s.contains("Raleigh"), s);
        assertTrue(s.contains("$elemMatch"), s);
        assertTrue(s.contains("Conference hall"), s);
    }

    // 4) Single inner leaf (no inner ops) inside elemMatch — should still wrap correctly
    @Test
    public void elemMatch_singleInnerLeaf() {
        String q =
                "dynamicAttributeSets.attributes:{value:\"Only One\"}";

        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$elemMatch", f.getName());
        assertEquals("dynamicAttributeSets.attributes", f.getField());

        String s = f.toString();
        assertTrue(s.contains("value"), s);
        assertTrue(s.contains("Only One"), s);
    }

    // 5) OR inside elemMatch (if grammar supports || in nested query)
    @Test
    public void elemMatch_inner_OR() {
        String q =
                "dynamicAttributeSets.attributes:{value:\"A\"||value:\"B\"}";

        Filter f = MorphiaUtils.convertToFilter(q, DummyModel.class);

        assertEquals("$elemMatch", f.getName());
        String s = f.toString();
        assertTrue(s.contains("$or") || s.contains("or"), s);
        assertTrue(s.contains("value"), s);
        assertTrue(s.contains("A"), s);
        assertTrue(s.contains("B"), s);
    }
}
