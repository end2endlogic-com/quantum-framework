package com.e2eq.framework.api.tools;

import java.util.Map;

/**
 * Central routing layer for tool execution. Resolves a tool by reference, validates permissions
 * and input, and dispatches to the appropriate backend by {@link com.e2eq.framework.model.persistent.tools.ToolType}.
 */
public interface ToolExecutor {

    /**
     * Execute a tool by reference name with given parameters.
     *
     * @param toolRef   tool refName (e.g. query_find, query_save)
     * @param parameters input parameters for the tool
     * @param context   execution context (realm, principal, trace)
     * @return result of execution
     */
    ToolResult execute(String toolRef, Map<String, Object> parameters, ExecutionContext context);
}
