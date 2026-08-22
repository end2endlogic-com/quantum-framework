package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A typed reference to an entity owned by another system. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class ExternalEntityReference {

    @NotBlank
    private String sourceSystem;

    @NotBlank
    private String entityType;

    @NotBlank
    private String externalId;

    private String displayName;
    private String canonicalUri;
    private Map<String, Object> additionalFields;
}
