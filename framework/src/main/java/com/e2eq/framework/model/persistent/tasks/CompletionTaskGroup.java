package com.e2eq.framework.model.persistent.tasks;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Date;

/**
 * Represents a grouping of {@link CompletionTask}s.  It can be used to
 * coordinate the execution and completion of multiple tasks.
 */
@Entity("completionTaskGroup")
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class CompletionTaskGroup extends BaseModel {

    public enum Status {
        NEW,
        RUNNING,
        COMPLETE
    }

    /** Human readable description of the group */
    protected String description;

    /** Current processing status of the group */
    protected Status status;

    protected Date createdDate;
    protected Date completedDate;

    @Override
    public String bmFunctionalArea() {
        return "TASK";
    }

    @Override
    public String bmFunctionalDomain() {
        return "COMPLETION_TASK_GROUP";
    }
}
