package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owner and explicit audience for participant-created work. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class TaskAccess {
    private String ownerRef;

    @Builder.Default
    private WorkItemVisibility visibility = WorkItemVisibility.PRIVATE;

    @Builder.Default
    private List<TaskGrant> grants = new ArrayList<>();

    public boolean isOwner(String actorRef) {
        return actorRef != null && Objects.equals(ownerRef, actorRef);
    }

    public boolean permits(String actorRef, WorkItemPermission permission) {
        if (isOwner(actorRef)) return true;
        if (visibility != WorkItemVisibility.RESTRICTED || grants == null) return false;
        return grants.stream()
                .filter(grant -> grant != null && Objects.equals(actorRef, grant.getPrincipalRef()))
                .anyMatch(grant -> grant.getPermissions() != null
                        && (grant.getPermissions().contains(permission)
                        || (permission == WorkItemPermission.VIEW
                        && (grant.getPermissions().contains(WorkItemPermission.COMMENT)
                        || grant.getPermissions().contains(WorkItemPermission.EDIT)))));
    }
}
