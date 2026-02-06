package com.e2eq.framework.model.persistent.agent;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.util.Map;

/**
 * Persisted agent conversation: realm-scoped, belongs to an agent and principal.
 * Used for continuity, audit, and context across turns.
 */
@RegisterForReflection
@Entity(value = "agentConversations", useDiscriminator = false)
public class AgentConversation {

    @Id
    private ObjectId id;

    /** Which agent definition (refName). */
    private String agentRef;

    /** Which user/principal. */
    private String principalId;

    /** Auto-generated or user-provided title. */
    private String title;

    /** ACTIVE, COMPLETED, ARCHIVED. */
    private String status = "ACTIVE";

    /** Number of turns. */
    private int turnCount;

    /** Total tokens used (when available from LLM). */
    private int totalTokensUsed;

    /** Compressed summary for long conversations. */
    private String summary;

    /** Context variables carried across turns. */
    private Map<String, Object> sharedContext;

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getAgentRef() {
        return agentRef;
    }

    public void setAgentRef(String agentRef) {
        this.agentRef = agentRef;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(int turnCount) {
        this.turnCount = turnCount;
    }

    public int getTotalTokensUsed() {
        return totalTokensUsed;
    }

    public void setTotalTokensUsed(int totalTokensUsed) {
        this.totalTokensUsed = totalTokensUsed;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Map<String, Object> getSharedContext() {
        return sharedContext;
    }

    public void setSharedContext(Map<String, Object> sharedContext) {
        this.sharedContext = sharedContext;
    }
}
