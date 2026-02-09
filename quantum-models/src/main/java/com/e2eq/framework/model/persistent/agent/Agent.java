package com.e2eq.framework.model.persistent.agent;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.IndexOptions;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent configuration: LLM + context (prompts). Used for obligation suggest and other AI features.
 * Realm-scoped. Apps (e.g. psa-app) use this for agent CRUD; framework provides the model and AgentRepo.
 */
@RegisterForReflection
@Entity(value = "agents", useDiscriminator = false)
public class Agent {

    @Id
    private ObjectId id;

    @Indexed(options = @IndexOptions(unique = true))
    private String refName;

    private String name;
    /** Reference to secret refName (LLM_API_KEY). */
    private String llmConfigRef;
    /** Ordered prompt steps (system/user) for context. */
    private List<PromptStep> context = new ArrayList<>();
    /** Tool names this agent can use (e.g. query_find, obligations_suggest). */
    private List<String> enabledTools;

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getRefName() {
        return refName;
    }

    public void setRefName(String refName) {
        this.refName = refName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLlmConfigRef() {
        return llmConfigRef;
    }

    public void setLlmConfigRef(String llmConfigRef) {
        this.llmConfigRef = llmConfigRef;
    }

    public List<PromptStep> getContext() {
        return context;
    }

    public void setContext(List<PromptStep> context) {
        this.context = context != null ? context : new ArrayList<>();
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools;
    }
}
