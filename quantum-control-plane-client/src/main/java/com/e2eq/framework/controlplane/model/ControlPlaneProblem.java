package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ControlPlaneProblem {
    @NotNull
    private String code;
    @NotNull
    private String message;

    @JsonProperty("code")
    public String getCode() { return code; }

    @JsonProperty("code")
    public void setCode(String code) { this.code = code; }

    @JsonProperty("message")
    public String getMessage() { return message; }

    @JsonProperty("message")
    public void setMessage(String message) { this.message = message; }
}
