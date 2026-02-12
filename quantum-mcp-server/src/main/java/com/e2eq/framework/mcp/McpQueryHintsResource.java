package com.e2eq.framework.mcp;

import com.e2eq.framework.api.agent.PermissionHintsProvider;
import com.e2eq.framework.api.agent.QueryHintsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Exposes query grammar hints and permission hints as MCP resources so AI clients
 * can inject them into LLM context for building valid BIAPI queries and answering
 * permission questions.
 *
 * <ul>
 *   <li>{@code quantum://query-hints} — BIAPI grammar summary, example queries by intent,
 *       and "did you know" tips (expand, ontology, wildcards)</li>
 *   <li>{@code quantum://permission-hints} — permission check/evaluate API summary,
 *       area/domain/action mapping, and examples for answering "can role X do Y?"</li>
 * </ul>
 *
 * @see QueryHintsProvider
 * @see PermissionHintsProvider
 */
public class McpQueryHintsResource {

    @Inject
    QueryHintsProvider queryHintsProvider;

    @Inject
    PermissionHintsProvider permissionHintsProvider;

    @Inject
    ObjectMapper objectMapper;

    @Resource(uri = "quantum://query-hints",
            description = "BIAPI query grammar summary, example queries (e.g. 'Locations in Atlanta' -> city:Atlanta), "
                    + "and 'did you know' hints (expand, ontology, wildcards). "
                    + "Use this to build valid query strings for query_find and query_plan.")
    ResourceResponse queryHints() {
        try {
            QueryHintsProvider.QueryHintsResponse hints = queryHintsProvider.getHints();
            String json = objectMapper.writeValueAsString(hints);
            return new ResourceResponse(List.of(
                    TextResourceContents.create("quantum://query-hints", json)));
        } catch (Exception e) {
            return new ResourceResponse(List.of(
                    TextResourceContents.create("quantum://query-hints",
                            "{\"error\":\"" + e.getMessage() + "\"}")));
        }
    }

    @Resource(uri = "quantum://permission-hints",
            description = "Permission check/evaluate API summary, area/domain/action mapping for the Query Gateway, "
                    + "example check requests, and hints for answering 'can role X perform action Y on entity Z?'")
    ResourceResponse permissionHints() {
        try {
            PermissionHintsProvider.PermissionHintsResponse hints = permissionHintsProvider.getHints();
            String json = objectMapper.writeValueAsString(hints);
            return new ResourceResponse(List.of(
                    TextResourceContents.create("quantum://permission-hints", json)));
        } catch (Exception e) {
            return new ResourceResponse(List.of(
                    TextResourceContents.create("quantum://permission-hints",
                            "{\"error\":\"" + e.getMessage() + "\"}")));
        }
    }
}
