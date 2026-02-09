package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Parameter definition for tool input/output schema. Used by {@link ToolDefinition}.
 */
@RegisterForReflection
public class ParameterDefinition {

    private String name;
    private String type;
    private String description;
    private boolean required;
    private Object defaultValue;
    private List<String> enumValues;
    private String jsonSchemaRef;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
    public List<String> getEnumValues() { return enumValues; }
    public void setEnumValues(List<String> enumValues) { this.enumValues = enumValues; }
    public String getJsonSchemaRef() { return jsonSchemaRef; }
    public void setJsonSchemaRef(String jsonSchemaRef) { this.jsonSchemaRef = jsonSchemaRef; }
}
