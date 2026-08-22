package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stable attribution for a user, service, or agent acting through Quantum.
 * Identity values are populated from the authenticated server context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class ActorReference {

    public enum ActorType {
        USER,
        SERVICE,
        AGENT,
        SYSTEM
    }

    @NotBlank
    private String actorId;

    @Builder.Default
    private ActorType actorType = ActorType.USER;

    private String displayName;
    private String realm;
    private String organizationRefName;
    private Map<String, Object> additionalFields;
}
