package com.e2eq.framework.model.persistent.agent;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

/**
 * One turn in an agent conversation: user, assistant, system, or tool message.
 * Stored per realm; conversationId links to {@link AgentConversation}.
 */
@RegisterForReflection
@Entity(value = "agentConversationTurns", useDiscriminator = false)
public class AgentConversationTurn {

    @Id
    private ObjectId id;

    /** Parent conversation id (ObjectId hex). */
    @Indexed
    private String conversationId;

    /** Order within conversation (0-based). */
    private int turnIndex;

    /** user, assistant, system, tool. */
    private String role;

    /** Message content. */
    private String content;

    /** Tools invoked in this turn (for assistant turns that requested tool calls). */
    private List<ToolInvocationRecord> toolCalls = new ArrayList<>();

    /** Tokens used (when available). */
    private int tokensUsed;

    /** Duration in ms. */
    private long durationMs;

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolInvocationRecord> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolInvocationRecord> toolCalls) {
        this.toolCalls = toolCalls != null ? toolCalls : new ArrayList<>();
    }

    public int getTokensUsed() {
        return tokensUsed;
    }

    public void setTokensUsed(int tokensUsed) {
        this.tokensUsed = tokensUsed;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
