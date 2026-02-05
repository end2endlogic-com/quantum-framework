package com.e2eq.framework.model.persistent.usage;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Kind of consumable token for usage-based billing and quotas.
 * Different types can be assigned to different sets of APIs and Tools/LLM configurations.
 *
 * @see TenantTokenAllocation
 * @see ApiCallUsageRecord
 * @see LlmUsageRecord
 */
@RegisterForReflection
public enum TokenType {

    /**
     * One unit per API call. Scope: API identifiers (area/domain/action).
     */
    API_CALL,

    /**
     * One unit per LLM/agent tool invocation. Scope: tool names (e.g. query_find, query_save).
     */
    LLM_REQUEST,

    /**
     * Input token count for LLM usage. Scope: tool names and/or LLM config keys.
     */
    LLM_INPUT_TOKENS,

    /**
     * Output token count for LLM usage. Scope: tool names and/or LLM config keys.
     */
    LLM_OUTPUT_TOKENS
}
