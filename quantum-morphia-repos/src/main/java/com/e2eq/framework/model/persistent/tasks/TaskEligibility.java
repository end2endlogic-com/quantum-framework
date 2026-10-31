package com.e2eq.framework.model.persistent.tasks;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Describes who may see and claim a work item. Empty role/profile/capability
 * sets mean that dimension does not further restrict eligibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class TaskEligibility {
    @Builder.Default
    private Set<WorkerType> workerTypes = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> roleRefs = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> profileRefs = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> capabilityRefs = new LinkedHashSet<>();

    public boolean permits(WorkerType workerType,
                           Set<String> roles,
                           Set<String> profiles,
                           Set<String> capabilities) {
        WorkerType effectiveWorkerType = workerType == null ? WorkerType.HUMAN : workerType;
        if (workerTypes == null || workerTypes.isEmpty()) {
            if (effectiveWorkerType != WorkerType.HUMAN) {
                return false;
            }
        } else if (!workerTypes.contains(effectiveWorkerType)) {
            return false;
        }

        return intersectsOrUnrestricted(roleRefs, roles)
                && intersectsOrUnrestricted(profileRefs, profiles)
                && intersectsOrUnrestricted(capabilityRefs, capabilities);
    }

    private static boolean intersectsOrUnrestricted(Set<String> required, Set<String> actual) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        return actual != null && required.stream().anyMatch(actual::contains);
    }
}
