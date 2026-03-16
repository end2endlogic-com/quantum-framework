package com.e2eq.framework.model.persistent.tasks;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Date;

/**
 * Simple task entity that can be tracked until completion.
 */
@Entity("completionTask")
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class CompletionTask extends BaseModel {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }

    @Reference
    protected CompletionTaskGroup group;

    protected String details;

    protected Status status;

    protected Date createdDate;
    protected Date completedDate;

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
