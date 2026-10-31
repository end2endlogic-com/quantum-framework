package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit participant access to a TODO. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class TaskGrant {
    private String principalRef;

    @Builder.Default
    private Set<WorkItemPermission> permissions = new LinkedHashSet<>();
}
