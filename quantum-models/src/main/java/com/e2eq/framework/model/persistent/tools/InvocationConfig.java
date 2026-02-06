package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * How to invoke a tool: HTTP method/path, body template, response mapping.
 * Used by {@link ToolDefinition}; interpretation depends on {@link ToolType}.
 */
@RegisterForReflection
public class InvocationConfig {

    /** HTTP method or invocation style. */
    private String method;
    /** Endpoint path or function reference. */
    private String path;
    private Map<String, String> headers;
    /** Template for request body using ${param.name} expressions. */
    private String bodyTemplate;
    /** JSONPath or expression to extract result. */
    private String responseMapping;
    private String contentType;

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    public String getResponseMapping() {
        return responseMapping;
    }

    public void setResponseMapping(String responseMapping) {
        this.responseMapping = responseMapping;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
