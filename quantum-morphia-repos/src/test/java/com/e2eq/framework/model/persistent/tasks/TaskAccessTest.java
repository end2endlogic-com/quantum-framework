package com.e2eq.framework.model.persistent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAccessTest {

    @Test
    void privateWorkIsVisibleOnlyToOwner() {
        TaskAccess access = TaskAccess.builder()
                .ownerRef("owner@example.com")
                .visibility(WorkItemVisibility.PRIVATE)
                .build();

        assertTrue(access.permits("owner@example.com", WorkItemPermission.EDIT));
        assertFalse(access.permits("other@example.com", WorkItemPermission.VIEW));
    }

    @Test
    void editAndCommentGrantsImplyViewWithoutTransferringOwnership() {
        TaskAccess access = TaskAccess.builder()
                .ownerRef("owner@example.com")
                .visibility(WorkItemVisibility.RESTRICTED)
                .grants(List.of(
                        TaskGrant.builder().principalRef("editor@example.com")
                                .permissions(Set.of(WorkItemPermission.EDIT)).build(),
                        TaskGrant.builder().principalRef("commenter@example.com")
                                .permissions(Set.of(WorkItemPermission.COMMENT)).build()))
                .build();

        assertTrue(access.permits("editor@example.com", WorkItemPermission.VIEW));
        assertTrue(access.permits("editor@example.com", WorkItemPermission.EDIT));
        assertTrue(access.permits("commenter@example.com", WorkItemPermission.VIEW));
        assertFalse(access.permits("commenter@example.com", WorkItemPermission.EDIT));
        assertFalse(access.isOwner("editor@example.com"));
    }
}
