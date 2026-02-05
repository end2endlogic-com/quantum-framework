package com.e2eq.framework.model.persistent.usage;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Single LLM/agent tool usage record for metering and billing.
 * Tracks by tenant and runAsUserId (effective user for agent execution).
 * Optional token counts support LLM input/output token-based billing.
 *
 * @see ApiCallUsageRecord
 * @see TenantTokenAllocation
 */
@RegisterForReflection
@Entity(value = "llm_usage", useDiscriminator = false)
public class LlmUsageRecord {

    @Id
    private ObjectId id;

    @Indexed
    private String realm;
    /** Effective user (runAs) when agent runs as a service user; otherwise caller. */
    @Indexed
    private String runAsUserId;
    /** Tool name (e.g. query_find, query_save). */
    @Indexed
    private String toolName;
    /** Optional LLM config key (e.g. for different models/endpoints). */
    private String llmConfigKey;
    /** When the call occurred. */
    @Indexed
    private Instant at;
    /** Optional input token count. */
    private Integer inputTokens;
    /** Optional output token count. */
    private Integer outputTokens;
    /** Allocation id that was debited (if any). */
    private ObjectId allocationId;

    public LlmUsageRecord() {
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getRunAsUserId() {
        return runAsUserId;
    }

    public void setRunAsUserId(String runAsUserId) {
        this.runAsUserId = runAsUserId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getLlmConfigKey() {
        return llmConfigKey;
    }

    public void setLlmConfigKey(String llmConfigKey) {
        this.llmConfigKey = llmConfigKey;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public ObjectId getAllocationId() {
        return allocationId;
    }

    public void setAllocationId(ObjectId allocationId) {
        this.allocationId = allocationId;
    }
}
