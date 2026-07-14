package com.e2eq.framework.model.persistent.tasks;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEligibilityTest {

    @Test
    void unrestrictedLegacyTaskIsHumanOnly() {
        TaskEligibility eligibility = new TaskEligibility();

        assertTrue(eligibility.permits(WorkerType.HUMAN, Set.of(), Set.of(), Set.of()));
        assertFalse(eligibility.permits(WorkerType.AGENT, Set.of(), Set.of(), Set.of()));
    }

    @Test
    void requiresEveryConfiguredEligibilityDimension() {
        TaskEligibility eligibility = TaskEligibility.builder()
                .workerTypes(Set.of(WorkerType.HUMAN, WorkerType.AGENT))
                .roleRefs(Set.of("planner"))
                .profileRefs(Set.of("asia-origin-team"))
                .capabilityRefs(Set.of("consolidation-approval"))
                .build();

        assertTrue(eligibility.permits(WorkerType.HUMAN,
                Set.of("planner"), Set.of("asia-origin-team"), Set.of("consolidation-approval")));
        assertFalse(eligibility.permits(WorkerType.HUMAN,
                Set.of("planner"), Set.of("europe-team"), Set.of("consolidation-approval")));
        assertFalse(eligibility.permits(WorkerType.SERVICE,
                Set.of("planner"), Set.of("asia-origin-team"), Set.of("consolidation-approval")));
    }
}
