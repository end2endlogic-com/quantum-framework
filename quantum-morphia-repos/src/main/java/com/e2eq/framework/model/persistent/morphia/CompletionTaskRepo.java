package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
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
        return createTask(getSecurityContextRealmId(), task, groupId);
    }

    public CompletionTask createTask(String realm, CompletionTask task, String groupId) {
        if (groupId != null) {
            Optional<CompletionTaskGroup> group = groupRepo.findById(new ObjectId(groupId), realm, true);
            group.ifPresent(task::setGroup);
        }
        task.setStatus(CompletionTask.Status.PENDING);
        task.setCreatedDate(new java.util.Date());
        CompletionTask saved = save(realm, task);
        if (groupId != null) {
            groupRepo.updateStatus(realm, groupId, CompletionTaskGroup.Status.RUNNING);
        }
        return saved;
    }

    public Optional<CompletionTask> completeTask(String id, CompletionTask.Status status, String result) {
        return completeTask(getSecurityContextRealmId(), id, status, result);
    }

    public Optional<CompletionTask> completeTask(String realm, String id, CompletionTask.Status status, String result) {
        Optional<CompletionTask> opt = findById(new ObjectId(id), realm, true);
        if (opt.isPresent()) {
            CompletionTask task = opt.get();
            task.setStatus(status);
            task.setCompletedDate(new java.util.Date());
            task.setResult(result);
            save(realm, task);
            if (task.getGroup() != null) {
                groupRepo.notifyGroup(task.getGroup().getId().toString(), "task:" + id + ":" + status);
                refreshGroupStatus(realm, task.getGroup().getId().toString());
            }
            return Optional.of(task);
        }
        return Optional.empty();
    }

    public List<CompletionTask> listByGroup(String realm, String groupId) {
        Optional<CompletionTaskGroup> group = groupRepo.findById(new ObjectId(groupId), realm, true);
        if (group.isEmpty()) {
            return List.of();
        }

        Query<CompletionTask> query = getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(CompletionTask.class)
                .filter(Filters.eq("group", group.get()));

        List<CompletionTask> tasks = new ArrayList<>();
        query.iterator().forEachRemaining(tasks::add);
        return tasks;
    }

    private void refreshGroupStatus(String realm, String groupId) {
        List<CompletionTask> tasks = listByGroup(realm, groupId);
        if (tasks.isEmpty()) {
            return;
        }

        boolean allTerminal = tasks.stream().allMatch(task ->
                task.getStatus() == CompletionTask.Status.SUCCESS || task.getStatus() == CompletionTask.Status.FAILED
        );

        groupRepo.updateStatus(
                realm,
                groupId,
                allTerminal ? CompletionTaskGroup.Status.COMPLETE : CompletionTaskGroup.Status.RUNNING
        );
    }
}
