package com.e2eq.framework.model.persistent.tasks;

import com.e2eq.framework.annotations.StateGraph;
import com.e2eq.framework.annotations.Stateful;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple task entity that can be tracked until completion.
 */
@Entity("completionTask")
@Indexes({
        @Index(fields = {@dev.morphia.annotations.Field("status"), @dev.morphia.annotations.Field("assignment.queueRef")}),
        @Index(fields = {@dev.morphia.annotations.Field("assignment.claimedBy"), @dev.morphia.annotations.Field("status")}),
        @Index(fields = {@dev.morphia.annotations.Field("kind"), @dev.morphia.annotations.Field("access.ownerRef"), @dev.morphia.annotations.Field("status")}),
        @Index(fields = {@dev.morphia.annotations.Field("kind"), @dev.morphia.annotations.Field("assignment.assigneeRef"), @dev.morphia.annotations.Field("status")}),
        @Index(fields = {@dev.morphia.annotations.Field("kind"), @dev.morphia.annotations.Field("access.grants.principalRef"), @dev.morphia.annotations.Field("status")}),
        @Index(fields = {@dev.morphia.annotations.Field("provenance.executionRef"), @dev.morphia.annotations.Field("provenance.stepKey")})
})
@RegisterForReflection
@Stateful
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class CompletionTask extends BaseModel {

    public enum Status {
        /** Legacy aliases retained for stored records and existing clients. */
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,

        OPEN,
        CLAIMED,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        EXPIRED;

        public boolean isTerminal() {
            return this == SUCCESS || this == FAILED || this == COMPLETED
                    || this == CANCELLED || this == EXPIRED;
        }
    }

    @Reference
    protected CompletionTaskGroup group;

    protected String details;

    @StateGraph(graphName = CompletionTaskStateGraph.GRAPH_NAME)
    protected Status status;

    protected String taskType;
    protected WorkItemKind kind;
    protected String summary;
    protected Integer priority;
    protected TaskSubject subject;
    protected TaskEligibility eligibility;
    protected TaskAssignment assignment;
    protected TaskContract contract;
    protected TaskProvenance provenance;
    protected TaskSla sla;
    protected TaskInteraction interaction;
    protected TaskAccess access;
    @Builder.Default
    protected List<TaskReminder> reminders = new ArrayList<>();
    @Builder.Default
    protected List<TaskActivity> activity = new ArrayList<>();
    protected TaskPayload inputPayload;
    protected TaskPayload resultPayload;

    protected Date createdDate;
    protected Date updatedDate;
    protected Date startedDate;
    protected Date completedDate;
    protected Date dueDate;

    protected String result;

    public CompletionTaskGroup getGroup() {
        return group;
    }

    public void setGroup(CompletionTaskGroup group) {
        this.group = group;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

    public Date getCompletedDate() {
        return completedDate;
    }

    public void setCompletedDate(Date completedDate) {
        this.completedDate = completedDate;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    @Override
    public String bmFunctionalArea() {
        return "TASK";
    }

    @Override
    public String bmFunctionalDomain() {
        return "COMPLETION_TASK";
    }
}
