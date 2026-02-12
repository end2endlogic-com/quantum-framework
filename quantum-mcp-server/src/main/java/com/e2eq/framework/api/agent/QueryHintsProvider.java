package com.e2eq.framework.api.agent;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides discoverable query-grammar hints and "did you know" tips for agents and developers.
 * Used by GET /api/agent/query-hints so the LLM can answer questions like "what is the query
 * string to retrieve locations in Atlanta?" and surface expand/ontology capabilities.
 *
 * @see AgentResource
 * @see com.e2eq.framework.api.query.QueryGatewayResource
 */
@ApplicationScoped
public class QueryHintsProvider {

    /**
     * Returns query grammar summary, example queries by intent, and "did you know" hints
     * so agents can suggest correct BIAPI query strings and use expand/ontology features.
     *
     * @return structured hints (queryGrammarSummary, exampleQueries, didYouKnow)
     */
    public QueryHintsResponse getHints() {
        QueryHintsResponse response = new QueryHintsResponse();
        response.queryGrammarSummary = buildQueryGrammarSummary();
        response.exampleQueries = buildExampleQueries();
        response.didYouKnow = buildDidYouKnow();
        return response;
    }

    private static Map<String, Object> buildQueryGrammarSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("syntax", "BIAPI query string (see Query Language Reference). Used in the 'query' argument for query_find, query_plan, query_deleteMany.");
        summary.put("operators", Map.of(
            "equals", "field:value or field:\"quoted\"",
            "notEquals", "field:!value",
            "comparisons", "field:>#n, field:>=##d, field:<#n, field:<=#n",
            "inList", "field:^[v1,v2]",
            "notInList", "field:!^[v1,v2]",
            "exists", "field:~",
            "nullCheck", "field:null or field:!null",
            "wildcards", "field:*sub* (contains), field:prefix* (starts with), field:*suffix (ends with)",
            "andOrNot", "&& (AND), || (OR), !! (NOT), () for grouping"
        ));
        summary.put("expand", "Use expand(path) to hydrate related entities in one result set. Paths are dotted; use [*] for array elements. Example: expand(customer) && status:ACTIVE. When expand(...) is present, the gateway uses AGGREGATION mode (requires feature.queryGateway.execution.enabled for execution).");
        summary.put("ontologyEdges", "Ontology-aware list endpoints and permission rules can use hasEdge/hasEdgeAny/notHasEdge (via ListQueryRewriter) to filter by ontology relationships. For gateway find, use attribute filters; for ontology-constrained lists, use the ontology list endpoint for the entity type when available.");
        return summary;
    }

    private static List<Map<String, Object>> buildExampleQueries() {
        List<Map<String, Object>> examples = new ArrayList<>();
        examples.add(map(
            "intent", "Locations in Atlanta",
            "query", "city:Atlanta",
            "rootType", "Location",
            "description", "Exact match on city. Use refName:*Atlanta* or address.city:Atlanta if the field name differs."
        ));
        examples.add(map(
            "intent", "Active locations in a region",
            "query", "status:ACTIVE && region:\"West\"",
            "rootType", "Location",
            "description", "Combine conditions with &&."
        ));
        examples.add(map(
            "intent", "Locations whose name contains 'Warehouse'",
            "query", "name:*Warehouse*",
            "rootType", "Location",
            "description", "Wildcard contains. Use name:Warehouse* for starts-with."
        ));
        examples.add(map(
            "intent", "Orders with customer hydrated",
            "query", "expand(customer) && status:ACTIVE",
            "rootType", "Order",
            "description", "Result set includes nested customer object. Requires AGGREGATION execution enabled."
        ));
        examples.add(map(
            "intent", "Orders with line-item products hydrated",
            "query", "expand(items[*].product) && status:ACTIVE",
            "rootType", "Order",
            "description", "Array reference expansion; each item gets a nested product."
        ));
        examples.add(map(
            "intent", "Entities by refName",
            "query", "refName:LOC-001",
            "rootType", "Location",
            "description", "Many entities have refName; use for stable business keys."
        ));
        examples.add(map(
            "intent", "Paged active list",
            "query", "status:ACTIVE",
            "rootType", "CodeList",
            "description", "Use with page: { limit: 10, skip: 0 } in query_find arguments."
        ));
        return examples;
    }

    private static List<Map<String, Object>> buildDidYouKnow() {
        List<Map<String, Object>> hints = new ArrayList<>();
        hints.add(map(
            "title", "You can use expand(path) to get a combined result set",
            "body", "Instead of fetching an entity and then its related entity in a second call, use expand(relationshipName) in the query string. The gateway returns root entities with the related entity inlined. Example: for Order with a customer reference, use expand(customer) && status:ACTIVE to get each order with its customer object embedded.",
            "exampleQuery", "expand(customer) && status:ACTIVE",
            "rootTypeExample", "Order"
        ));
        hints.add(map(
            "title", "You can expand array references with [*]",
            "body", "For collections (e.g. order line items each referencing a product), use expand(items[*].product) to hydrate every item's product in one request. The result set looks like: each order has an items array, and each item has a nested product object.",
            "exampleQuery", "expand(items[*].product) && status:ACTIVE",
            "rootTypeExample", "Order"
        ));
        hints.add(map(
            "title", "Check the plan before running a query with expand",
            "body", "Call query_plan with the same rootType and query to see whether the gateway will use FILTER or AGGREGATION mode and which expand paths were detected. Useful to confirm expand(...) is recognized before executing query_find.",
            "exampleQuery", "expand(customer) && status:ACTIVE",
            "rootTypeExample", "Order"
        ));
        hints.add(map(
            "title", "Ontology relationships can filter list results",
            "body", "When the application uses ontology edges (hasEdge, hasEdgeAny, notHasEdge), ontology-aware list endpoints can filter entities by relationship (e.g. 'orders placed in org X'). For the generic query gateway find, use attribute filters; for ontology-constrained lists, use the resource's ontology list endpoint when available.",
            "exampleQuery", null,
            "rootTypeExample", null
        ));
        hints.add(map(
            "title", "Wildcards and numeric/date prefixes",
            "body", "Strings: use *sub* for contains, prefix* for starts with. Numbers: use # for integer (quantity:#5) and ## for decimal (price:##19.99). Dates: use ISO format (createdDate:>=2024-01-01).",
            "exampleQuery", "name:*Atlanta* && status:ACTIVE",
            "rootTypeExample", "Location"
        ));
        return hints;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @RegisterForReflection
    public static class QueryHintsResponse {
        /** Short summary of query grammar (operators, expand, ontology). */
        public Map<String, Object> queryGrammarSummary;
        /** Example queries by intent (intent, query, rootType, description). */
        public List<Map<String, Object>> exampleQueries;
        /** "Did you know" hints (title, body, exampleQuery, rootTypeExample). */
        public List<Map<String, Object>> didYouKnow;
    }
}
