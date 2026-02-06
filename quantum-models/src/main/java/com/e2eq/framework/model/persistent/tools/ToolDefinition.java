package com.e2eq.framework.model.persistent.tools;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.IndexOptions;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Map;

/**
 * Persisted definition of a tool: identity, schema, invocation, and behavioral hints.
 * Tools are realm-scoped; stored per realm via {@link com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo}.
 * Used by the tool registry, tool executor, and agent tool resolution.
 */
@RegisterForReflection
@Entity(value = "toolDefinitions", useDiscriminator = false)
public class ToolDefinition {

    @Id
    private ObjectId id;

    /** Unique reference within realm (e.g. query_find, query_save). Used for lookup and execute. */
    @Indexed(options = @IndexOptions(unique = true))
    private String refName;

    /** Semantic name for LLM tool selection (e.g. search_customers, solve_vrp). */
    private String name;
    /** Natural language description for LLM. */
    private String description;
    /** Grouping: quantum.crud, helix.optimization, external. */
    private String category;
    private List<String> tags;

    /** Parameters with types and descriptions. */
    private Map<String, ParameterDefinition> inputSchema;
    private Map<String, ParameterDefinition> outputSchema;
    /** Full JSON Schema (auto-generated or manual override). */
    private String inputJsonSchema;
    private String outputJsonSchema;

    private ToolType toolType;
    private InvocationConfig invocation;
    /** Reference to ToolProviderConfig for connection details (for EXTERNAL_*). */
    private String providerRef;

    private boolean hasSideEffects;
    private boolean idempotent;
    /** e.g. "fast", "medium", "slow". */
    private String estimatedLatency;
    /** e.g. "free", "low", "high". */
    private String costHint;
    private boolean requiresConfirmation;

    /** HTTP status → retry/clarify/fail semantics. */
    private Map<Integer, ErrorSemantics> errorHandling;
    private int defaultRetryCount;
    private String defaultRetryBackoff;

    /** e.g. ["tool", "workflowStep", "mcpTool"]. */
    private List<String> availableAs;
    private boolean enabled = true;
    /** Permission URI for access control. */
    private String securityUri;

    private String longDescription;
    private List<ToolExample> examples;

    /**
     * Source of this definition: "manual", "auto-generated". Auto-generated tools
     * are not overwritten when re-running generation if manually customized.
     */
    private String source = "manual";

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Map<String, ParameterDefinition> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, ParameterDefinition> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public Map<String, ParameterDefinition> getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(Map<String, ParameterDefinition> outputSchema) {
        this.outputSchema = outputSchema;
    }

    public String getInputJsonSchema() {
        return inputJsonSchema;
    }

    public void setInputJsonSchema(String inputJsonSchema) {
        this.inputJsonSchema = inputJsonSchema;
    }

    public String getOutputJsonSchema() {
        return outputJsonSchema;
    }

    public void setOutputJsonSchema(String outputJsonSchema) {
        this.outputJsonSchema = outputJsonSchema;
    }

    public ToolType getToolType() {
        return toolType;
    }

    public void setToolType(ToolType toolType) {
        this.toolType = toolType;
    }

    public InvocationConfig getInvocation() {
        return invocation;
    }

    public void setInvocation(InvocationConfig invocation) {
        this.invocation = invocation;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public boolean isHasSideEffects() {
        return hasSideEffects;
    }

    public void setHasSideEffects(boolean hasSideEffects) {
        this.hasSideEffects = hasSideEffects;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public void setIdempotent(boolean idempotent) {
        this.idempotent = idempotent;
    }

    public String getEstimatedLatency() {
        return estimatedLatency;
    }

    public void setEstimatedLatency(String estimatedLatency) {
        this.estimatedLatency = estimatedLatency;
    }

    public String getCostHint() {
        return costHint;
    }

    public void setCostHint(String costHint) {
        this.costHint = costHint;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public Map<Integer, ErrorSemantics> getErrorHandling() {
        return errorHandling;
    }

    public void setErrorHandling(Map<Integer, ErrorSemantics> errorHandling) {
        this.errorHandling = errorHandling;
    }

    public int getDefaultRetryCount() {
        return defaultRetryCount;
    }

    public void setDefaultRetryCount(int defaultRetryCount) {
        this.defaultRetryCount = defaultRetryCount;
    }

    public String getDefaultRetryBackoff() {
        return defaultRetryBackoff;
    }

    public void setDefaultRetryBackoff(String defaultRetryBackoff) {
        this.defaultRetryBackoff = defaultRetryBackoff;
    }

    public List<String> getAvailableAs() {
        return availableAs;
    }

    public void setAvailableAs(List<String> availableAs) {
        this.availableAs = availableAs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecurityUri() {
        return securityUri;
    }

    public void setSecurityUri(String securityUri) {
        this.securityUri = securityUri;
    }

    public String getLongDescription() {
        return longDescription;
    }

    public void setLongDescription(String longDescription) {
        this.longDescription = longDescription;
    }

    public List<ToolExample> getExamples() {
        return examples;
    }

    public void setExamples(List<ToolExample> examples) {
        this.examples = examples;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
