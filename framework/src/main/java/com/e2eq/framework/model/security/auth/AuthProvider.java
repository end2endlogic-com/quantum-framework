package com.e2eq.framework.model.security.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;

import java.util.Set;

public interface AuthProvider {
    SecurityIdentity validateToken(String token);

    @RegisterForReflection
    record LoginResponse(
            @JsonProperty("accessToken") String accessToken,
            @JsonProperty("refreshToken") String refreshToken,
            @JsonProperty("identity") SecurityIdentity identity,
            @JsonProperty("roles") Set<String> roles
    ) {}

    LoginResponse login(String userId, String password);

    LoginResponse refreshTokens(String refreshToken);
}
