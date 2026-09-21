package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class QueryToFilterListenerEdgeFilterTest {

    public static class TestOrderModel extends UnversionedBaseModel {
        protected String orderNumber;
        protected String status;

        @Override
        public String bmFunctionalArea() {
            return "sales";
        }

        @Override
        public String bmFunctionalDomain() {
            return "orders";
        }
    }

    private static class CapturingQueryToFilterListener extends QueryToFilterListener {
        String capturedPredicate;
        String capturedDst;
        String capturedSrc;
        Filter capturedEdgeFilter;
        boolean wasSrcLookup = false;
        boolean wasDstLookup = false;
        Set<String> mockReturnIds = Set.of("order-101", "order-102");

        public CapturingQueryToFilterListener(Map<String, String> variableMap, Class<? extends UnversionedBaseModel> modelClass) {
            super(variableMap, modelClass);
        }

        @Override
        protected Set<String> ontologySrcIdsByDst(DataDomain dataDomain, String predicate, String dst, Filter edgeFilter) {
            this.capturedPredicate = predicate;
            this.capturedDst = dst;
            this.capturedEdgeFilter = edgeFilter;
            this.wasSrcLookup = true;
            return mockReturnIds;
        }

        @Override
        protected Set<String> ontologyDstIdsBySrc(DataDomain dataDomain, String predicate, String src, Filter edgeFilter) {
            this.capturedPredicate = predicate;
            this.capturedSrc = src;
            this.capturedEdgeFilter = edgeFilter;
            this.wasDstLookup = true;
            return mockReturnIds;
        }
    }

    private Map<String, String> defaultVars;

    @BeforeEach
    public void setUp() {
        defaultVars = Map.of(
                "pTenantId", "tenant-1",
                "orgRefName", "org-acme",
                "pAccountId", "acc-001",
                "pDataSegment", "0"
        );
    }

    private CapturingQueryToFilterListener parse(String query) {
        CharStream cs = CharStreams.fromString(query);
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        BIAPIQueryParser.QueryContext tree = parser.query();

        CapturingQueryToFilterListener listener = new CapturingQueryToFilterListener(defaultVars, TestOrderModel.class);
        ParseTreeWalker.DEFAULT.walk(listener, tree);
        return listener;
    }

    @Test
    public void testLegacyTwoArgumentHasEdge() {
        String query = "hasEdge(\"assignedTo\", \"loc1\")";
        CapturingQueryToFilterListener listener = parse(query);

        assertTrue(listener.wasSrcLookup);
        assertEquals("assignedTo", listener.capturedPredicate);
        assertEquals("loc1", listener.capturedDst);
        assertNull(listener.capturedEdgeFilter, "Legacy 2-argument hasEdge must have null edgeFilter");

        Filter filter = listener.getFilter();
        assertNotNull(filter);
        assertEquals("refName", filter.getField());
    }

    @Test
    public void testThreeArgumentHasEdgeSingleProperty() {
        String query = "hasEdge(\"assignedTo\", \"loc1\", { role: \"PRIMARY\" })";
        CapturingQueryToFilterListener listener = parse(query);

        assertTrue(listener.wasSrcLookup);
        assertEquals("assignedTo", listener.capturedPredicate);
        assertEquals("loc1", listener.capturedDst);
        assertNotNull(listener.capturedEdgeFilter, "Edge filter must be parsed");

        // Property 'role' must be scoped under 'props.role'
        assertInstanceOf(dev.morphia.query.filters.RegexFilter.class, listener.capturedEdgeFilter);
        dev.morphia.query.filters.RegexFilter rf = (dev.morphia.query.filters.RegexFilter) listener.capturedEdgeFilter;
        assertEquals("props.role", rf.getField());
        assertEquals("^PRIMARY$", rf.pattern().pattern());
    }

    @Test
    public void testThreeArgumentHasEdgeCompoundProperties() {
        String query = "hasEdge(\"assignedTo\", \"loc1\", { role: \"PRIMARY\" && status: \"ACTIVE\" })";
        CapturingQueryToFilterListener listener = parse(query);

        assertTrue(listener.wasSrcLookup);
        assertNotNull(listener.capturedEdgeFilter);

        Filter edgeFilter = listener.capturedEdgeFilter;
        assertEquals("$and", edgeFilter.getName());
        String str = edgeFilter.toString();
        assertTrue(str.contains("props.role"), str);
        assertTrue(str.contains("props.status"), str);
    }

    @Test
    public void testRootEdgeFieldPreservation() {
        // dstType is a root field on OntologyEdge; role is an open property
        String query = "hasEdge(\"assignedTo\", \"loc1\", { dstType: \"Location\" && role: \"PRIMARY\" })";
        CapturingQueryToFilterListener listener = parse(query);

        assertNotNull(listener.capturedEdgeFilter);
        String str = listener.capturedEdgeFilter.toString();
        // dstType should NOT become props.dstType
        assertTrue(str.contains("dstType"), str);
        assertFalse(str.contains("props.dstType"), str);
        // role SHOULD become props.role
        assertTrue(str.contains("props.role"), str);
    }

    @Test
    public void testExplicitPropsPrefixNotDoubled() {
        String query = "hasEdge(\"assignedTo\", \"loc1\", { props.priority: 1 })";
        CapturingQueryToFilterListener listener = parse(query);

        assertNotNull(listener.capturedEdgeFilter);
        assertEquals("props.priority", listener.capturedEdgeFilter.getField());
    }

    @Test
    public void testHasOutgoingEdgeAliasWithFilter() {
        String query = "hasOutgoingEdge(\"assignedTo\", \"loc1\", { role: \"PRIMARY\" })";
        CapturingQueryToFilterListener listener = parse(query);

        assertTrue(listener.wasSrcLookup);
        assertEquals("assignedTo", listener.capturedPredicate);
        assertEquals("loc1", listener.capturedDst);
        assertNotNull(listener.capturedEdgeFilter);
        assertEquals("props.role", listener.capturedEdgeFilter.getField());
    }

    @Test
    public void testHasIncomingEdgeWithFilter() {
        String query = "hasIncomingEdge(\"canSeeLocation\", \"assoc-1\", { role: \"PRIMARY\" })";
        CapturingQueryToFilterListener listener = parse(query);

        assertTrue(listener.wasDstLookup);
        assertEquals("canSeeLocation", listener.capturedPredicate);
        assertEquals("assoc-1", listener.capturedSrc);
        assertNotNull(listener.capturedEdgeFilter);
        assertEquals("props.role", listener.capturedEdgeFilter.getField());
    }

    @Test
    public void testTopLevelQueryCombiningAttributeAndEdgeFilter() {
        String query = "status:OPEN && hasEdge(\"assignedTo\", \"loc1\", { role: \"PRIMARY\" })";
        CapturingQueryToFilterListener listener = parse(query);

        Filter filter = listener.getFilter();
        assertNotNull(filter);
        assertEquals("$and", filter.getName());

        assertNotNull(listener.capturedEdgeFilter);
        assertEquals("props.role", listener.capturedEdgeFilter.getField());
    }

    @Test
    public void testValidatingQueryToFilterListenerIgnoresEdgeProperties() {
        // ValidatingQueryToFilterListener validates field names on TestOrderModel.
        // TestOrderModel does not have 'role' or 'edgeWeight', but they should not be rejected
        // because they are inside the edgeFilter.
        CharStream cs = CharStreams.fromString("status:OPEN && hasEdge(\"assignedTo\", \"loc1\", { role: \"PRIMARY\" && edgeWeight: 10 })");
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        BIAPIQueryParser.QueryContext tree = parser.query();

        class ValidatingCapturingListener extends ValidatingQueryToFilterListener {
            public ValidatingCapturingListener(Map<String, String> variableMap, Class<? extends UnversionedBaseModel> modelClass) {
                super(variableMap, modelClass);
            }
            @Override
            protected Set<String> ontologySrcIdsByDst(DataDomain dataDomain, String predicate, String dst, Filter edgeFilter) {
                return Set.of("order-1");
            }
        }

        ValidatingCapturingListener listener = new ValidatingCapturingListener(defaultVars, TestOrderModel.class);
        ParseTreeWalker.DEFAULT.walk(listener, tree);

        assertFalse(listener.hasValidationErrors(), "Should not report validation errors for edge properties: " + listener.getValidationErrors());
    }

    @Test
    public void testTextInsideEdgeFilterThrowsException() {
        String query = "hasEdge(\"assignedTo\", \"loc1\", { text(\"invalid\") })";
        CharStream cs = CharStreams.fromString(query);
        BIAPIQueryLexer lexer = new BIAPIQueryLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
        BIAPIQueryParser.QueryContext tree = parser.query();

        CapturingQueryToFilterListener listener = new CapturingQueryToFilterListener(defaultVars, TestOrderModel.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            ParseTreeWalker.DEFAULT.walk(listener, tree);
        });

        assertTrue(ex.getMessage().contains("text(...) cannot be used inside an edgeFilter expression"),
                "Unexpected exception message: " + ex.getMessage());
    }
}
