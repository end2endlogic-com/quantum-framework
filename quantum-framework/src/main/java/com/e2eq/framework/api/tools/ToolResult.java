package com.e2eq.framework.api.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Result of a single tool execution from {@link ToolExecutor}.
 */
@RegisterForReflection
public class ToolResult {

    private String toolRef;
    private ToolResultStatus status;
    /** Successful result data. */
    private Map<String, Object> data;
    private String errorMessage;
    /** Original HTTP status if applicable. */
    private Integer httpStatus;
    private long durationMs;
    private Map<String, String> metadata;

    public String getToolRef() {
        return toolRef;
    }

    public void setToolRef(String toolRef) {
        this.toolRef = toolRef;
    }

    public ToolResultStatus getStatus() {
        return status;
    }

    public void setStatus(ToolResultStatus status) {
        this.status = status;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    @RegisterForReflection
    public enum ToolResultStatus {
        SUCCESS,
        ERROR,
        TIMEOUT,
        PERMISSION_DENIED,
        VALIDATION_ERROR
    }
}
