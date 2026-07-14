package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** A governed in-app trigger attached to, but distinct from, a work item. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class TaskReminder {
    private String reminderId;
    private Date triggerAt;
    private String createdBy;

    @Builder.Default
    private List<String> recipientRefs = new ArrayList<>();

    @Builder.Default
    private String channel = "IN_APP";

    @Builder.Default
    private ReminderStatus status = ReminderStatus.SCHEDULED;

    private Date deliveredAt;
    private Date cancelledAt;
}
