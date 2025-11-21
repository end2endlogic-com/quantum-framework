package com.e2eq.framework.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Set;

@RegisterForReflection
public record RoleAssignment(
        @JsonProperty("role") String role,
        @JsonProperty("sources") Set<RoleSource> sources
) {}
