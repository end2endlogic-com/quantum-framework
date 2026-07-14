package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompletionTaskMorphiaMappingTest {

    @Test
    void nestedDocumentsUsedByQueriesAndIndexesAreMappedEntities() {
        assertNotNull(TaskSubject.class.getAnnotation(Entity.class),
                "subject must be mapped so subject.workbookRef is a valid path");
        assertNotNull(TaskAssignment.class.getAnnotation(Entity.class),
                "assignment must be mapped so assignment.queueRef and assignment.claimedBy are valid paths");
        assertNotNull(TaskProvenance.class.getAnnotation(Entity.class),
                "provenance must be mapped so provenance.executionRef and provenance.stepKey are valid paths");
        assertNotNull(TaskAccess.class.getAnnotation(Entity.class),
                "access must be mapped so owner and participant indexes are valid paths");
        assertNotNull(TaskGrant.class.getAnnotation(Entity.class),
                "grants must be mapped so access.grants.principalRef is queryable");
        assertNotNull(TaskReminder.class.getAnnotation(Entity.class),
                "reminders must be mapped as governed embedded triggers");
        assertNotNull(TaskActivity.class.getAnnotation(Entity.class),
                "activity must be mapped as attributable embedded history");
    }
}
