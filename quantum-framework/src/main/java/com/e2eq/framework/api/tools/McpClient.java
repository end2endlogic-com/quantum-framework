package com.e2eq.framework.api.tools;

import java.util.List;
import java.util.Map;

/**
 * Client for the Model Context Protocol (MCP). Used to list tools from a remote MCP server
 * and to call a tool by name with arguments. Supports HTTP (Streamable HTTP) transport:
 * JSON-RPC over POST.
 */
public interface McpClient {

    /**
     * Lists tools exposed by the MCP server at the given base URL.
     *
     * @param baseUrl      MCP endpoint URL (e.g. https://example.com/mcp)
     * @param authHeaders  optional auth headers (e.g. Authorization: Bearer ...)
     * @return list of tool descriptors (name, description, inputSchema); empty on error
     */
    List<McpToolDescriptor> listTools(String baseUrl, Map<String, String> authHeaders);

    /**
     * Calls a tool on the MCP server by name with the given arguments.
     *
     * @param baseUrl      MCP endpoint URL
     * @param toolName     tool name (as returned by listTools)
     * @param arguments    tool arguments (JSON-like map)
     * @param authHeaders  optional auth headers
     * @return result content (e.g. list of content items or text); null on error
     */
    McpCallResult callTool(String baseUrl, String toolName, Map<String, Object> arguments,
                           Map<String, String> authHeaders);

    /**
     * Descriptor of a tool as returned by MCP tools/list.
     */
    record McpToolDescriptor(String name, String description, Object inputSchema) {}

    /**
     * Result of MCP tools/call. MCP returns content array (e.g. type "text" with "text" field).
     */
    record McpCallResult(boolean isError, String errorMessage, List<Map<String, Object>> content) {
        public static McpCallResult error(String message) {
            return new McpCallResult(true, message, List.of());
        }
        public static McpCallResult ok(List<Map<String, Object>> content) {
            return new McpCallResult(false, null, content != null ? content : List.of());
        }
    }
}
