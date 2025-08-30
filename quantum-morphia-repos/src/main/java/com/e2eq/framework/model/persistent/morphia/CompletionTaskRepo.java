package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Repository for {@link CompletionTask} entities.  Basic helper methods are
 * provided to create tasks and mark them as completed.
 */
@ApplicationScoped
public class CompletionTaskRepo extends MorphiaRepo<CompletionTask> {

    @Inject
    CompletionTaskGroupRepo groupRepo;

    public CompletionTask createTask(CompletionTask task, String groupId) {
        if (groupId != null) {
            Optional<CompletionTaskGroup> group = groupRepo.findById(groupId);
            group.ifPresent(task::setGroup);
        }
        task.setStatus(CompletionTask.Status.PENDING);
        task.setCreatedDate(new java.util.Date());
        return save(task);
    }

    public Optional<CompletionTask> completeTask(String id, CompletionTask.Status status, String result) {
        Optional<CompletionTask> opt = findById(id);
        if (opt.isPresent()) {
            CompletionTask task = opt.get();
            task.setStatus(status);
            task.setCompletedDate(new java.util.Date());
            task.setResult(result);
            save(task);
            if (task.getGroup() != null) {
                groupRepo.notifyGroup(task.getGroup().getId().toString(), "task:" + id + ":" + status);
            }
            return Optional.of(task);
        }
        return Optional.empty();
    }
}
