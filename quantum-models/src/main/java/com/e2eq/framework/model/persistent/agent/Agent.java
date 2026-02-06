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
 * Agent = LLM + context (ordered prompts). Stored per realm.
 * References an LLM config (e.g. realm default); context is the list of prompt steps
 * that set the kinds of questions this agent answers. Tool access can be defined by
 * explicit refs, categories, and tags (with exclusions and limit); when those are
 * unset, {@link #enabledTools} is used for backward compatibility.
 */
@RegisterForReflection
@Entity(value = "agents", useDiscriminator = false)
public class Agent {

    @Id
    private ObjectId id;

    /** Unique ref within realm (e.g. OBLIGATIONS_SUGGEST, OBLIGATIONS_PERSIST). */
    @Indexed(options = @IndexOptions(unique = true))
    private String refName;

    /** Display name. */
    private String name;

    /** Optional description of what this agent does. */
    private String description;

    /**
     * LLM config ref (e.g. a tenant-defined ref name for an LLM provider config).
     * When null or blank, use realm default LLM config.
     */
    private String llmConfigRef;

    /** Ordered prompt steps (context) sent to the LLM before user input. */
    private List<PromptStep> context = new ArrayList<>();

    /**
     * Explicit tool refs (e.g. query_find, query_save). When null/empty with no categories/tags,
     * resolution falls back to {@link #enabledTools}.
     */
    private List<String> toolRefs;

    /** Include all tools in these categories (e.g. quantum.crud). */
    private List<String> toolCategories;

    /** Include all tools with any of these tags. */
    private List<String> toolTags;

    /** Exclude these tool refs from the resolved set. */
    private List<String> excludedToolRefs;

    /** Max tools to send to LLM (0 = no limit). */
    private int maxToolsInContext;

    /** Other agents this agent can invoke as tools (Phase 3+). */
    private List<String> delegateAgentRefs;

    /** Output format hint: "text", "json", "structured". */
    private String responseFormat;

    /** Permission URI for this agent. */
    private String securityUri;

    /** Service account / identity this agent acts as. */
    private String principalRef;

    /** Realms this agent can operate in (empty = current realm only). */
    private List<String> allowedRealms;

    /** When true, human approval required before side-effect tools. */
    private boolean requiresApproval;

    /** Whether this agent is enabled. */
    private Boolean enabled;

    /**
     * Optional: tool names this agent is allowed to use (legacy).
     * When {@link #toolRefs}, {@link #toolCategories}, and {@link #toolTags} are all null/empty,
     * resolution uses this list as explicit refs; otherwise this field is ignored.
     */
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public List<String> getToolRefs() {
        return toolRefs;
    }

    public void setToolRefs(List<String> toolRefs) {
        this.toolRefs = toolRefs;
    }

    public List<String> getToolCategories() {
        return toolCategories;
    }

    public void setToolCategories(List<String> toolCategories) {
        this.toolCategories = toolCategories;
    }

    public List<String> getToolTags() {
        return toolTags;
    }

    public void setToolTags(List<String> toolTags) {
        this.toolTags = toolTags;
    }

    public List<String> getExcludedToolRefs() {
        return excludedToolRefs;
    }

    public void setExcludedToolRefs(List<String> excludedToolRefs) {
        this.excludedToolRefs = excludedToolRefs;
    }

    public int getMaxToolsInContext() {
        return maxToolsInContext;
    }

    public void setMaxToolsInContext(int maxToolsInContext) {
        this.maxToolsInContext = maxToolsInContext;
    }

    public List<String> getDelegateAgentRefs() {
        return delegateAgentRefs;
    }

    public void setDelegateAgentRefs(List<String> delegateAgentRefs) {
        this.delegateAgentRefs = delegateAgentRefs;
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(String responseFormat) {
        this.responseFormat = responseFormat;
    }

    public String getSecurityUri() {
        return securityUri;
    }

    public void setSecurityUri(String securityUri) {
        this.securityUri = securityUri;
    }

    public String getPrincipalRef() {
        return principalRef;
    }

    public void setPrincipalRef(String principalRef) {
        this.principalRef = principalRef;
    }

    public List<String> getAllowedRealms() {
        return allowedRealms;
    }

    public void setAllowedRealms(List<String> allowedRealms) {
        this.allowedRealms = allowedRealms;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }

    public void setRequiresApproval(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public void setEnabledTools(List<String> enabledTools) {
        this.enabledTools = enabledTools;
    }
}
