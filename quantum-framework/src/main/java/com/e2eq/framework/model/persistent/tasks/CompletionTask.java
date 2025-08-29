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

    @Override
    public String bmFunctionalArea() {
        return "TASK";
    }

    @Override
    public String bmFunctionalDomain() {
        return "COMPLETION_TASK";
    }
}
