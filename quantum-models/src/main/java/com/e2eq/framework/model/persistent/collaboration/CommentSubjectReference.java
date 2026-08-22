package com.e2eq.framework.model.persistent.collaboration;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ExternalEntityReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Exactly one local Quantum entity or external entity identifies a comment subject. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class CommentSubjectReference {

    @Valid
    private EntityReference quantumEntity;

    @Valid
    private ExternalEntityReference externalEntity;

    @AssertTrue(message = "Exactly one of quantumEntity or externalEntity is required")
    @JsonIgnore
    public boolean isTargetValid() {
        return (quantumEntity == null) != (externalEntity == null);
    }
}
