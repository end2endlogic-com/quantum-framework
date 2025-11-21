package com.e2eq.framework.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum RoleSource {
    @JsonProperty("usergroup") USERGROUP,
    @JsonProperty("idp") IDP,
    @JsonProperty("credential") CREDENTIAL
}
