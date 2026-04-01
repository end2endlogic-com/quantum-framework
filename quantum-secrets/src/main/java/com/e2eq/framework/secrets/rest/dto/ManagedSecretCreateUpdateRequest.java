package com.e2eq.framework.secrets.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ManagedSecretCreateUpdateRequest {

    private String refName;
    private String secretType;
    private String displayName;
    private String description;
    private String providerType;
    private Boolean realmDefault;
    private String value;
}
