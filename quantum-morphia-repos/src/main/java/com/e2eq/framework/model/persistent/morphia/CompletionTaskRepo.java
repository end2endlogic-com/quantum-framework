package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import com.e2eq.framework.model.persistent.tasks.TaskAssignment;
import com.e2eq.framework.model.persistent.tasks.TaskAccess;
import com.e2eq.framework.model.persistent.tasks.TaskActivity;
import com.e2eq.framework.model.persistent.tasks.TaskGrant;
import com.e2eq.framework.model.persistent.tasks.TaskEligibility;
import com.e2eq.framework.model.persistent.tasks.TaskPayload;
import com.e2eq.framework.model.persistent.tasks.TaskProvenance;
import com.e2eq.framework.model.persistent.tasks.TaskReminder;
import com.e2eq.framework.model.persistent.tasks.ReminderStatus;
import com.e2eq.framework.model.persistent.tasks.WorkItemOperationException;
import com.e2eq.framework.model.persistent.tasks.WorkItemKind;
import com.e2eq.framework.model.persistent.tasks.WorkItemPermission;
import com.e2eq.framework.model.persistent.tasks.WorkItemVisibility;
import com.e2eq.framework.model.persistent.tasks.WorkerType;
import com.mongodb.client.model.ReturnDocument;
import dev.morphia.ModifyOptions;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperator;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for legacy completion tasks and governed work items. The work-item
 * methods enforce eligibility, ownership, leases, state transitions, and
 * optimistic revision checks at the persistence boundary.
 */
@ApplicationScoped
public class CompletionTaskRepo extends MorphiaRepo<CompletionTask> {

    private static final int MAX_QUEUE_PAGE_SIZE = 200;

    @Inject
    CompletionTaskGroupRepo groupRepo;

    /** Legacy creation path retained for existing onboarding integrations. */
    public CompletionTask createTask(CompletionTask task, String groupId) {
        return createTask(getSecurityContextRealmId(), task, groupId);
    }

    /** Legacy creation path retained for existing onboarding integrations. */
    public CompletionTask createTask(String realm, CompletionTask task, String groupId) {
        attachGroup(realm, task, groupId);
        Date now = new Date();
        task.setStatus(CompletionTask.Status.PENDING);
        task.setCreatedDate(now);
        task.setUpdatedDate(now);
        CompletionTask saved = save(realm, task);
        markGroupRunning(realm, groupId);
        return saved;
    }

    public CompletionTask createWorkItem(CompletionTask task, String groupId, String createdBy) {
        return createWorkItem(getSecurityContextRealmId(), task, groupId, createdBy);
    }

    public CompletionTask createWorkItem(String realm,
                                         CompletionTask task,
                                         String groupId,
                                         String createdBy) {
        validateNewWorkItem(task);
        if (task.getKind() == null) {
            task.setKind(WorkItemKind.PROCESS_TASK);
        }
        attachGroup(realm, task, groupId);
        Date now = new Date();
        task.setStatus(CompletionTask.Status.OPEN);
        task.setCreatedDate(now);
        task.setUpdatedDate(now);
        if (task.getPriority() == null) {
            task.setPriority(0);
        }
        if (task.getEligibility() == null) {
            task.setEligibility(TaskEligibility.builder()
                    .workerTypes(new LinkedHashSet<>(Set.of(WorkerType.HUMAN)))
                    .build());
        }
        if (task.getAssignment() == null) {
            task.setAssignment(TaskAssignment.builder().queueRef("default").build());
        }
        if (task.getProvenance() != null && task.getProvenance().getCreatedBy() == null) {
            task.getProvenance().setCreatedBy(createdBy);
        }
        CompletionTask saved = save(realm, task);
        markGroupRunning(realm, groupId);
        return saved;
    }

    /**
     * Create participant-owned work. Ownership, creator attribution, initial
     * visibility, and responsibility are server assigned and cannot be widened
     * by a client supplied body.
     */
    public CompletionTask createParticipantWorkItem(CompletionTask task, String createdBy) {
        validateNewWorkItem(task);
        Date now = new Date();
        task.setKind(WorkItemKind.TODO);
        task.setStatus(CompletionTask.Status.OPEN);
        task.setCreatedDate(now);
        task.setUpdatedDate(now);
        task.setCompletedDate(null);
        task.setResult(null);
        task.setPriority(task.getPriority() == null ? 0 : task.getPriority());
        task.setEligibility(TaskEligibility.builder()
                .workerTypes(new LinkedHashSet<>(Set.of(WorkerType.HUMAN)))
                .build());
        task.setAssignment(TaskAssignment.builder()
                .assigneeRef(createdBy)
                .assignedBy(createdBy)
                .assignedAt(now)
                .build());
        task.setAccess(TaskAccess.builder()
                .ownerRef(createdBy)
                .visibility(WorkItemVisibility.PRIVATE)
                .build());
        TaskProvenance provenance = task.getProvenance() == null
                ? TaskProvenance.builder().build() : task.getProvenance();
        provenance.setCreatedBy(createdBy);
        task.setProvenance(provenance);
        task.setReminders(new ArrayList<>());
        task.setActivity(new ArrayList<>(List.of(activity("CREATED", createdBy, "Private to-do created", now))));
        return save(getSecurityContextRealmId(), task);
    }

    /** Indexed participant projection; queue claiming semantics remain unchanged. */
    public List<CompletionTask> listParticipating(String actorRef, String workbookRef, int limit) {
        if (actorRef == null || actorRef.isBlank()) {
            throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE, "An authenticated actor is required");
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUEUE_PAGE_SIZE));
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("kind", WorkItemKind.TODO));
        if (workbookRef != null && !workbookRef.isBlank()) {
            filters.add(Filters.eq("subject.workbookRef", workbookRef));
        }
        filters.add(Filters.or(
                Filters.eq("access.ownerRef", actorRef),
                Filters.eq("assignment.assigneeRef", actorRef),
                Filters.eq("access.grants.principalRef", actorRef)));
        Filter[] securedFilters = getFilterArray(filters, CompletionTask.class);
        List<CompletionTask> result = new ArrayList<>();
        getMorphiaDataStoreWrapper().getDataStore(getSecurityContextRealmId())
                .find(CompletionTask.class)
                .filter(securedFilters)
                .iterator(new FindOptions().sort(Sort.descending("updatedDate")).limit(safeLimit))
                .forEachRemaining(task -> {
                    if (canViewParticipant(task, actorRef)) result.add(task);
                });
        return result;
    }

    public CompletionTask getParticipantVisible(String id, String actorRef) {
        CompletionTask task = requireParticipantTask(id);
        requireParticipantView(task, actorRef);
        return task;
    }

    public CompletionTask updateParticipantWorkItem(String id,
                                                    long expectedVersion,
                                                    String actorRef,
                                                    String summary,
                                                    String details,
                                                    Date dueDate) {
        CompletionTask current = requireParticipantTask(id);
        requireParticipantEdit(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        if (summary == null || summary.isBlank()) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST, "summary is required");
        }
        Date now = new Date();
        List<TaskActivity> activity = appendActivity(current, "UPDATED", actorRef, "To-do details updated", now);
        return atomicParticipantUpdate(current, expectedVersion,
                UpdateOperators.set("summary", summary.trim()),
                UpdateOperators.set("details", blankToNull(details)),
                UpdateOperators.set("dueDate", dueDate),
                UpdateOperators.set("activity", activity),
                UpdateOperators.set("updatedDate", now));
    }

    public CompletionTask shareParticipantWorkItem(String id,
                                                   long expectedVersion,
                                                   String actorRef,
                                                   WorkItemVisibility visibility,
                                                   List<TaskGrant> grants) {
        CompletionTask current = requireParticipantTask(id);
        requireOwner(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        List<TaskGrant> normalized = normalizeGrants(grants, actorRef);
        WorkItemVisibility effectiveVisibility = normalized.isEmpty()
                ? WorkItemVisibility.PRIVATE : WorkItemVisibility.RESTRICTED;
        if (visibility != null && visibility != effectiveVisibility) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST,
                    "visibility must be PRIVATE with no grants or RESTRICTED with at least one grant");
        }
        TaskAccess access = TaskAccess.builder()
                .ownerRef(actorRef)
                .visibility(effectiveVisibility)
                .grants(normalized)
                .build();
        Date now = new Date();
        return atomicParticipantUpdate(current, expectedVersion,
                UpdateOperators.set("access", access),
                UpdateOperators.set("activity", appendActivity(current, "SHARED", actorRef,
                        normalized.isEmpty() ? "Sharing removed" : "Shared with " + normalized.size() + " participant(s)", now)),
                UpdateOperators.set("updatedDate", now));
    }

    public CompletionTask assignParticipantWorkItem(String id,
                                                    long expectedVersion,
                                                    String actorRef,
                                                    String assigneeRef) {
        CompletionTask current = requireParticipantTask(id);
        requireOwner(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        String effectiveAssignee = blankToNull(assigneeRef);
        if (effectiveAssignee == null) effectiveAssignee = actorRef;
        if (!Objects.equals(effectiveAssignee, actorRef)
                && (current.getAccess() == null
                || !current.getAccess().permits(effectiveAssignee, WorkItemPermission.EDIT))) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST,
                    "The assignee must have EDIT access to the work item");
        }
        Date now = new Date();
        return atomicParticipantUpdate(current, expectedVersion,
                UpdateOperators.set("assignment.assigneeRef", effectiveAssignee),
                UpdateOperators.set("assignment.assignedBy", actorRef),
                UpdateOperators.set("assignment.assignedAt", now),
                UpdateOperators.set("activity", appendActivity(current, "ASSIGNED", actorRef,
                        "Assigned to " + effectiveAssignee, now)),
                UpdateOperators.set("updatedDate", now));
    }

    public CompletionTask addParticipantReminder(String id,
                                                 long expectedVersion,
                                                 String actorRef,
                                                 Date triggerAt,
                                                 List<String> recipientRefs) {
        CompletionTask current = requireParticipantTask(id);
        requireParticipantEdit(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        if (triggerAt == null || !triggerAt.after(new Date())) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST, "triggerAt must be in the future");
        }
        List<String> recipients = recipientRefs == null || recipientRefs.isEmpty()
                ? List.of(actorRef)
                : recipientRefs.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (recipients.isEmpty() || recipients.stream().anyMatch(ref -> !canViewParticipant(current, ref))) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST,
                    "Every reminder recipient must be able to view the work item");
        }
        Date now = new Date();
        TaskReminder reminder = TaskReminder.builder()
                .reminderId(UUID.randomUUID().toString())
                .triggerAt(triggerAt)
                .createdBy(actorRef)
                .recipientRefs(new ArrayList<>(recipients))
                .build();
        List<TaskReminder> reminders = new ArrayList<>(safeList(current.getReminders()));
        reminders.add(reminder);
        return atomicParticipantUpdate(current, expectedVersion,
                UpdateOperators.set("reminders", reminders),
                UpdateOperators.set("activity", appendActivity(current, "REMINDER_SCHEDULED", actorRef,
                        "Reminder scheduled for " + triggerAt, now)),
                UpdateOperators.set("updatedDate", now));
    }

    public CompletionTask cancelParticipantReminder(String id,
                                                    String reminderId,
                                                    long expectedVersion,
                                                    String actorRef) {
        CompletionTask current = requireParticipantTask(id);
        requireParticipantEdit(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        List<TaskReminder> reminders = new ArrayList<>(safeList(current.getReminders()));
        TaskReminder reminder = reminders.stream()
                .filter(value -> value != null && Objects.equals(reminderId, value.getReminderId()))
                .findFirst()
                .orElseThrow(() -> failure(WorkItemOperationException.Code.NOT_FOUND,
                        "Reminder " + reminderId + " was not found"));
        if (!Objects.equals(actorRef, reminder.getCreatedBy())
                && (current.getAccess() == null || !current.getAccess().isOwner(actorRef))) {
            throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "Only the reminder creator or work-item owner may cancel it");
        }
        Date now = new Date();
        reminder.setStatus(ReminderStatus.CANCELLED);
        reminder.setCancelledAt(now);
        return atomicParticipantUpdate(current, expectedVersion,
                UpdateOperators.set("reminders", reminders),
                UpdateOperators.set("activity", appendActivity(current, "REMINDER_CANCELLED", actorRef,
                        "Reminder cancelled", now)),
                UpdateOperators.set("updatedDate", now));
    }

    public CompletionTask completeParticipantWorkItem(String id, long expectedVersion, String actorRef) {
        return transitionParticipantWorkItem(id, expectedVersion, actorRef,
                CompletionTask.Status.OPEN, CompletionTask.Status.COMPLETED, "COMPLETED", "To-do completed");
    }

    public CompletionTask reopenParticipantWorkItem(String id, long expectedVersion, String actorRef) {
        return transitionParticipantWorkItem(id, expectedVersion, actorRef,
                CompletionTask.Status.COMPLETED, CompletionTask.Status.OPEN, "REOPENED", "To-do reopened");
    }

    public List<CompletionTask> listEligible(WorkerType workerType,
                                             Set<String> roles,
                                             Set<String> profiles,
                                             Set<String> capabilities,
                                             String queueRef,
                                             int limit) {
        String realm = getSecurityContextRealmId();
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUEUE_PAGE_SIZE));
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.in("status", List.of(CompletionTask.Status.OPEN, CompletionTask.Status.PENDING)));
        if (queueRef != null && !queueRef.isBlank()) {
            filters.add(Filters.eq("assignment.queueRef", queueRef));
        }

        Filter[] securedFilters = getFilterArray(filters, CompletionTask.class);
        Query<CompletionTask> query = getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(CompletionTask.class)
                .filter(securedFilters);
        FindOptions options = new FindOptions()
                .sort(Sort.descending("priority"), Sort.ascending("createdDate"))
                .limit(MAX_QUEUE_PAGE_SIZE);

        List<CompletionTask> eligible = new ArrayList<>();
        query.iterator(options).forEachRemaining(task -> {
            if (eligible.size() < safeLimit && isEligible(task, workerType, roles, profiles, capabilities)) {
                eligible.add(task);
            }
        });
        return eligible;
    }

    public List<CompletionTask> listClaimedBy(String actorRef, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_QUEUE_PAGE_SIZE));
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("assignment.claimedBy", actorRef));
        filters.add(Filters.in("status", List.of(
                CompletionTask.Status.CLAIMED,
                CompletionTask.Status.IN_PROGRESS)));
        Filter[] securedFilters = getFilterArray(filters, CompletionTask.class);

        List<CompletionTask> tasks = new ArrayList<>();
        getMorphiaDataStoreWrapper().getDataStore(getSecurityContextRealmId())
                .find(CompletionTask.class)
                .filter(securedFilters)
                .iterator(new FindOptions().sort(Sort.descending("priority")).limit(safeLimit))
                .forEachRemaining(tasks::add);
        return tasks;
    }

    /**
     * Resolve a deep-linked work item without weakening queue visibility. A
     * worker may read its own claim, or an open item it is currently eligible
     * to claim. All reads still pass through the realm security filter.
     */
    public CompletionTask getVisible(String id,
                                     String actorRef,
                                     WorkerType workerType,
                                     Set<String> roles,
                                     Set<String> profiles,
                                     Set<String> capabilities) {
        CompletionTask task = requireTask(getSecurityContextRealmId(), id);
        TaskAssignment assignment = task.getAssignment();
        if (assignment != null && Objects.equals(actorRef, assignment.getClaimedBy())) {
            return task;
        }
        if ((task.getStatus() == CompletionTask.Status.OPEN
                || task.getStatus() == CompletionTask.Status.PENDING)
                && isEligible(task, workerType, roles, profiles, capabilities)) {
            return task;
        }
        throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE,
                "Actor may not view work item " + id);
    }

    public CompletionTask claim(String id,
                                long expectedVersion,
                                String actorRef,
                                WorkerType workerType,
                                Set<String> roles,
                                Set<String> profiles,
                                Set<String> capabilities,
                                Duration leaseDuration) {
        String realm = getSecurityContextRealmId();
        CompletionTask current = requireTask(realm, id);
        requireExpectedVersion(current, expectedVersion);
        if (!isEligible(current, workerType, roles, profiles, capabilities)) {
            throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "Actor is not eligible to claim work item " + id);
        }
        requireActionAllowed(current, "CLAIM");
        if (current.getStatus() != CompletionTask.Status.OPEN
                && current.getStatus() != CompletionTask.Status.PENDING) {
            throw invalidTransition(current, CompletionTask.Status.CLAIMED);
        }

        Date now = new Date();
        Date leaseUntil = leaseDuration == null ? null : Date.from(now.toInstant().plus(leaseDuration));
        return atomicTransition(realm, current, expectedVersion, actorRef,
                List.of(CompletionTask.Status.OPEN, CompletionTask.Status.PENDING),
                CompletionTask.Status.CLAIMED,
                UpdateOperators.set("assignment.claimedBy", actorRef),
                UpdateOperators.set("assignment.claimedWorkerType", workerType),
                UpdateOperators.set("assignment.claimedAt", now),
                UpdateOperators.set("assignment.heartbeatAt", now),
                UpdateOperators.set("assignment.leaseUntil", leaseUntil));
    }

    public CompletionTask start(String id, long expectedVersion, String actorRef) {
        requireActionAllowed(requireTask(getSecurityContextRealmId(), id), "START");
        return ownedTransition(id, expectedVersion, actorRef,
                List.of(CompletionTask.Status.CLAIMED), CompletionTask.Status.IN_PROGRESS,
                UpdateOperators.set("startedDate", new Date()));
    }

    public CompletionTask complete(String id,
                                   long expectedVersion,
                                   String actorRef,
                                   String expectedAssessmentVersion,
                                   String result,
                                   TaskPayload resultPayload) {
        requireActionAllowed(requireTask(getSecurityContextRealmId(), id), "COMPLETE");
        CompletionTask current = requireTask(getSecurityContextRealmId(), id);
        requireAssessmentVersion(current, expectedAssessmentVersion);
        TaskPayload capturedPayload = validateAndAttributeResult(current, resultPayload, actorRef);
        CompletionTask task = ownedTransition(id, expectedVersion, actorRef,
                List.of(CompletionTask.Status.CLAIMED, CompletionTask.Status.IN_PROGRESS),
                CompletionTask.Status.COMPLETED,
                UpdateOperators.set("result", result),
                UpdateOperators.set("resultPayload", capturedPayload),
                UpdateOperators.set("completedDate", new Date()));
        notifyGroup(task);
        return task;
    }

    public CompletionTask fail(String id,
                               long expectedVersion,
                               String actorRef,
                               String result,
                               TaskPayload resultPayload) {
        requireActionAllowed(requireTask(getSecurityContextRealmId(), id), "FAIL");
        CompletionTask current = requireTask(getSecurityContextRealmId(), id);
        TaskPayload capturedPayload = validateAndAttributeResult(current, resultPayload, actorRef);
        CompletionTask task = ownedTransition(id, expectedVersion, actorRef,
                List.of(CompletionTask.Status.CLAIMED, CompletionTask.Status.IN_PROGRESS),
                CompletionTask.Status.FAILED,
                UpdateOperators.set("result", result),
                UpdateOperators.set("resultPayload", capturedPayload),
                UpdateOperators.set("completedDate", new Date()));
        notifyGroup(task);
        return task;
    }

    public CompletionTask release(String id, long expectedVersion, String actorRef) {
        requireActionAllowed(requireTask(getSecurityContextRealmId(), id), "RELEASE");
        return ownedTransition(id, expectedVersion, actorRef,
                List.of(CompletionTask.Status.CLAIMED, CompletionTask.Status.IN_PROGRESS),
                CompletionTask.Status.OPEN,
                UpdateOperators.unset("assignment.claimedBy"),
                UpdateOperators.unset("assignment.claimedWorkerType"),
                UpdateOperators.unset("assignment.claimedAt"),
                UpdateOperators.unset("assignment.heartbeatAt"),
                UpdateOperators.unset("assignment.leaseUntil"));
    }

    public CompletionTask heartbeat(String id,
                                    long expectedVersion,
                                    String actorRef,
                                    Duration leaseDuration) {
        String realm = getSecurityContextRealmId();
        CompletionTask current = requireTask(realm, id);
        requireActionAllowed(current, "HEARTBEAT");
        requireOwnedAndLive(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        Date now = new Date();
        Date leaseUntil = Date.from(now.toInstant().plus(leaseDuration));
        return atomicUpdate(realm, current, expectedVersion, actorRef,
                List.of(CompletionTask.Status.CLAIMED, CompletionTask.Status.IN_PROGRESS),
                UpdateOperators.set("assignment.heartbeatAt", now),
                UpdateOperators.set("assignment.leaseUntil", leaseUntil),
                UpdateOperators.set("updatedDate", now));
    }

    public CompletionTask cancel(String id, long expectedVersion) {
        String realm = getSecurityContextRealmId();
        CompletionTask current = requireTask(realm, id);
        requireExpectedVersion(current, expectedVersion);
        requireActionAllowed(current, "CANCEL");
        List<CompletionTask.Status> allowed = List.of(
                CompletionTask.Status.OPEN,
                CompletionTask.Status.CLAIMED,
                CompletionTask.Status.IN_PROGRESS);
        if (!allowed.contains(current.getStatus())) {
            throw invalidTransition(current, CompletionTask.Status.CANCELLED);
        }
        CompletionTask task = atomicTransition(realm, current, expectedVersion, null,
                allowed, CompletionTask.Status.CANCELLED,
                UpdateOperators.set("completedDate", new Date()));
        notifyGroup(task);
        return task;
    }

    public CompletionTask expire(String id, long expectedVersion) {
        String realm = getSecurityContextRealmId();
        CompletionTask current = requireTask(realm, id);
        requireExpectedVersion(current, expectedVersion);
        List<CompletionTask.Status> allowed = List.of(
                CompletionTask.Status.OPEN,
                CompletionTask.Status.CLAIMED,
                CompletionTask.Status.IN_PROGRESS);
        if (!allowed.contains(current.getStatus())) {
            throw invalidTransition(current, CompletionTask.Status.EXPIRED);
        }
        CompletionTask task = atomicTransition(realm, current, expectedVersion, null,
                allowed, CompletionTask.Status.EXPIRED,
                UpdateOperators.set("completedDate", new Date()));
        notifyGroup(task);
        return task;
    }

    /** Legacy completion path retained for existing clients. */
    public Optional<CompletionTask> completeTask(String id, CompletionTask.Status status, String result) {
        return completeTask(getSecurityContextRealmId(), id, status, result);
    }

    /** Legacy completion path retained for existing clients. */
    public Optional<CompletionTask> completeTask(String realm, String id, CompletionTask.Status status, String result) {
        Optional<CompletionTask> opt = findById(new ObjectId(id), realm, true);
        if (opt.isPresent()) {
            CompletionTask task = opt.get();
            task.setStatus(status);
            task.setCompletedDate(new Date());
            task.setUpdatedDate(new Date());
            task.setResult(result);
            save(realm, task);
            notifyGroup(realm, task);
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

    private CompletionTask ownedTransition(String id,
                                           long expectedVersion,
                                           String actorRef,
                                           List<CompletionTask.Status> allowedStatuses,
                                           CompletionTask.Status targetStatus,
                                           UpdateOperator... updates) {
        String realm = getSecurityContextRealmId();
        CompletionTask current = requireTask(realm, id);
        requireOwnedAndLive(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        if (!allowedStatuses.contains(current.getStatus())) {
            throw invalidTransition(current, targetStatus);
        }
        return atomicTransition(realm, current, expectedVersion, actorRef,
                allowedStatuses, targetStatus, updates);
    }

    private CompletionTask transitionParticipantWorkItem(String id,
                                                         long expectedVersion,
                                                         String actorRef,
                                                         CompletionTask.Status expectedStatus,
                                                         CompletionTask.Status targetStatus,
                                                         String action,
                                                         String detail) {
        CompletionTask current = requireParticipantTask(id);
        requireParticipantEdit(current, actorRef);
        requireExpectedVersion(current, expectedVersion);
        if (current.getStatus() != expectedStatus) {
            throw invalidTransition(current, targetStatus);
        }
        try {
            stateGraphManager.validateTransition(
                    com.e2eq.framework.model.persistent.tasks.CompletionTaskStateGraph.GRAPH_NAME,
                    expectedStatus.name(), targetStatus.name());
        } catch (InvalidStateTransitionException e) {
            throw failure(WorkItemOperationException.Code.INVALID_TRANSITION, e.getMessage());
        }
        Date now = new Date();
        List<UpdateOperator> updates = new ArrayList<>();
        updates.add(UpdateOperators.set("status", targetStatus));
        updates.add(UpdateOperators.set("completedDate",
                targetStatus == CompletionTask.Status.COMPLETED ? now : null));
        updates.add(UpdateOperators.set("activity", appendActivity(current, action, actorRef, detail, now)));
        updates.add(UpdateOperators.set("updatedDate", now));
        return atomicParticipantUpdate(current, expectedVersion,
                updates.toArray(new UpdateOperator[0]));
    }

    private CompletionTask atomicParticipantUpdate(CompletionTask current,
                                                   long expectedVersion,
                                                   UpdateOperator... updates) {
        List<UpdateOperator> allUpdates = new ArrayList<>();
        allUpdates.add(UpdateOperators.inc("version", 1L));
        allUpdates.addAll(Arrays.asList(updates));
        CompletionTask updated = getMorphiaDataStoreWrapper().getDataStore(getSecurityContextRealmId())
                .find(CompletionTask.class)
                .filter(Filters.eq("_id", current.getId()),
                        Filters.eq("version", expectedVersion),
                        Filters.eq("kind", WorkItemKind.TODO))
                .modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                        allUpdates.get(0), allUpdates.subList(1, allUpdates.size()).toArray(new UpdateOperator[0]));
        if (updated == null) {
            throw failure(WorkItemOperationException.Code.REVISION_CONFLICT,
                    "Work item changed before the operation could be applied; refresh and retry");
        }
        return updated;
    }

    private CompletionTask atomicTransition(String realm,
                                            CompletionTask current,
                                            long expectedVersion,
                                            String actorRef,
                                            List<CompletionTask.Status> allowedStatuses,
                                            CompletionTask.Status targetStatus,
                                            UpdateOperator... updates) {
        try {
            stateGraphManager.validateTransition(
                    com.e2eq.framework.model.persistent.tasks.CompletionTaskStateGraph.GRAPH_NAME,
                    current.getStatus().name(), targetStatus.name());
        } catch (InvalidStateTransitionException e) {
            throw new WorkItemOperationException(
                    WorkItemOperationException.Code.INVALID_TRANSITION, e.getMessage());
        }

        List<UpdateOperator> allUpdates = new ArrayList<>();
        allUpdates.add(UpdateOperators.set("status", targetStatus));
        allUpdates.add(UpdateOperators.set("updatedDate", new Date()));
        allUpdates.add(UpdateOperators.inc("version", 1L));
        allUpdates.addAll(Arrays.asList(updates));
        return atomicModify(realm, current, expectedVersion, actorRef, allowedStatuses, allUpdates);
    }

    private CompletionTask atomicUpdate(String realm,
                                        CompletionTask current,
                                        long expectedVersion,
                                        String actorRef,
                                        List<CompletionTask.Status> allowedStatuses,
                                        UpdateOperator... updates) {
        List<UpdateOperator> allUpdates = new ArrayList<>();
        allUpdates.add(UpdateOperators.inc("version", 1L));
        allUpdates.addAll(Arrays.asList(updates));
        return atomicModify(realm, current, expectedVersion, actorRef, allowedStatuses, allUpdates);
    }

    private CompletionTask atomicModify(String realm,
                                        CompletionTask current,
                                        long expectedVersion,
                                        String actorRef,
                                        List<CompletionTask.Status> allowedStatuses,
                                        List<UpdateOperator> updates) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("_id", current.getId()));
        filters.add(Filters.eq("version", expectedVersion));
        filters.add(Filters.in("status", allowedStatuses));
        if (actorRef != null && current.getStatus() != CompletionTask.Status.OPEN
                && current.getStatus() != CompletionTask.Status.PENDING) {
            filters.add(Filters.eq("assignment.claimedBy", actorRef));
        }

        CompletionTask updated = getMorphiaDataStoreWrapper().getDataStore(realm)
                .find(CompletionTask.class)
                .filter(filters.toArray(new Filter[0]))
                .modify(new ModifyOptions().returnDocument(ReturnDocument.AFTER),
                        updates.get(0), updates.subList(1, updates.size()).toArray(new UpdateOperator[0]));
        if (updated == null) {
            throw failure(WorkItemOperationException.Code.REVISION_CONFLICT,
                    "Work item changed before the operation could be applied; refresh and retry");
        }
        return updated;
    }

    private CompletionTask requireTask(String realm, String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST, "Invalid work item id");
        }
        return findById(new ObjectId(id), realm, false)
                .orElseThrow(() -> failure(WorkItemOperationException.Code.NOT_FOUND,
                        "Work item " + id + " was not found"));
    }

    private CompletionTask requireParticipantTask(String id) {
        CompletionTask task = requireTask(getSecurityContextRealmId(), id);
        if (task.getKind() != WorkItemKind.TODO || task.getAccess() == null) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST,
                    "Work item " + id + " is not participant-owned work");
        }
        return task;
    }

    private void requireParticipantView(CompletionTask task, String actorRef) {
        if (!canViewParticipant(task, actorRef)) {
            throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "Actor may not view work item " + task.getId());
        }
    }

    private void requireParticipantEdit(CompletionTask task, String actorRef) {
        TaskAccess access = task.getAccess();
        boolean assigned = task.getAssignment() != null
                && Objects.equals(actorRef, task.getAssignment().getAssigneeRef());
        if (!assigned && (access == null || !access.permits(actorRef, WorkItemPermission.EDIT))) {
            throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "Actor may not edit work item " + task.getId());
        }
    }

    private void requireOwner(CompletionTask task, String actorRef) {
        if (task.getAccess() == null || !task.getAccess().isOwner(actorRef)) {
            throw failure(WorkItemOperationException.Code.NOT_ELIGIBLE,
                    "Only the work-item owner may change sharing or assignment");
        }
    }

    private boolean canViewParticipant(CompletionTask task, String actorRef) {
        if (task == null || actorRef == null || task.getKind() != WorkItemKind.TODO) return false;
        if (task.getAssignment() != null && Objects.equals(actorRef, task.getAssignment().getAssigneeRef())) {
            return true;
        }
        return task.getAccess() != null && task.getAccess().permits(actorRef, WorkItemPermission.VIEW);
    }

    private List<TaskGrant> normalizeGrants(List<TaskGrant> grants, String ownerRef) {
        if (grants == null) return List.of();
        java.util.LinkedHashMap<String, TaskGrant> unique = new java.util.LinkedHashMap<>();
        for (TaskGrant grant : grants) {
            if (grant == null || grant.getPrincipalRef() == null || grant.getPrincipalRef().isBlank()) continue;
            String principal = grant.getPrincipalRef().trim();
            if (Objects.equals(principal, ownerRef)) continue;
            Set<WorkItemPermission> permissions = grant.getPermissions() == null
                    ? new LinkedHashSet<>() : new LinkedHashSet<>(grant.getPermissions());
            if (permissions.isEmpty()) permissions.add(WorkItemPermission.VIEW);
            permissions.add(WorkItemPermission.VIEW);
            unique.put(principal, TaskGrant.builder()
                    .principalRef(principal)
                    .permissions(permissions)
                    .build());
        }
        return new ArrayList<>(unique.values());
    }

    private List<TaskActivity> appendActivity(CompletionTask task,
                                              String action,
                                              String actorRef,
                                              String detail,
                                              Date occurredAt) {
        List<TaskActivity> result = new ArrayList<>(safeList(task.getActivity()));
        result.add(activity(action, actorRef, detail, occurredAt));
        return result;
    }

    private static TaskActivity activity(String action, String actorRef, String detail, Date occurredAt) {
        return TaskActivity.builder()
                .action(action)
                .actorRef(actorRef)
                .detail(detail)
                .occurredAt(occurredAt)
                .build();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void requireExpectedVersion(CompletionTask task, long expectedVersion) {
        if (task.getVersion() == null || task.getVersion() != expectedVersion) {
            throw failure(WorkItemOperationException.Code.REVISION_CONFLICT,
                    "Expected revision " + expectedVersion + " but found " + task.getVersion());
        }
    }

    private void requireAssessmentVersion(CompletionTask task, String expectedAssessmentVersion) {
        String taskAssessmentVersion = task.getSubject() == null
                ? null : task.getSubject().getAssessmentVersion();
        if (taskAssessmentVersion == null || taskAssessmentVersion.isBlank()) {
            return;
        }
        if (!Objects.equals(taskAssessmentVersion, expectedAssessmentVersion)) {
            throw failure(WorkItemOperationException.Code.REVISION_CONFLICT,
                    "Decision assessment changed before completion; refresh the work item and review the current evidence");
        }
    }

    private void requireOwnedAndLive(CompletionTask task, String actorRef) {
        TaskAssignment assignment = task.getAssignment();
        if (assignment == null || !Objects.equals(actorRef, assignment.getClaimedBy())) {
            throw failure(WorkItemOperationException.Code.NOT_ASSIGNED,
                    "Work item is not assigned to actor " + actorRef);
        }
        if (assignment.getLeaseUntil() != null && assignment.getLeaseUntil().before(new Date())) {
            throw failure(WorkItemOperationException.Code.LEASE_EXPIRED,
                    "Work item lease has expired and must be reclaimed");
        }
    }

    private boolean isEligible(CompletionTask task,
                               WorkerType workerType,
                               Set<String> roles,
                               Set<String> profiles,
                               Set<String> capabilities) {
        TaskEligibility eligibility = task.getEligibility();
        if (eligibility == null) {
            return workerType == null || workerType == WorkerType.HUMAN;
        }
        return eligibility.permits(workerType, safeSet(roles), safeSet(profiles), safeSet(capabilities));
    }

    private Set<String> safeSet(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    private void validateNewWorkItem(CompletionTask task) {
        if (task == null) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST, "Work item body is required");
        }
        if (task.getTaskType() == null || task.getTaskType().isBlank()) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST, "taskType is required");
        }
        if (task.getSummary() == null || task.getSummary().isBlank()) {
            throw failure(WorkItemOperationException.Code.INVALID_REQUEST, "summary is required");
        }
    }

    private void requireActionAllowed(CompletionTask task, String action) {
        if (task.getContract() == null || task.getContract().getAllowedActions() == null
                || task.getContract().getAllowedActions().isEmpty()) {
            return;
        }
        boolean allowed = task.getContract().getAllowedActions().stream()
                .anyMatch(configured -> action.equalsIgnoreCase(configured));
        if (!allowed) {
            throw failure(WorkItemOperationException.Code.INVALID_TRANSITION,
                    "Action " + action + " is not enabled for work item " + task.getId());
        }
    }

    private TaskPayload validateAndAttributeResult(CompletionTask task,
                                                   TaskPayload payload,
                                                   String actorRef) {
        String requiredSchema = task.getContract() == null
                ? null : task.getContract().getResultSchemaRef();
        if (requiredSchema != null && !requiredSchema.isBlank()) {
            if (payload == null || payload.getSchemaRef() == null) {
                throw failure(WorkItemOperationException.Code.INVALID_REQUEST,
                        "A result payload using schema " + requiredSchema + " is required");
            }
            if (!requiredSchema.equals(payload.getSchemaRef())) {
                throw failure(WorkItemOperationException.Code.INVALID_REQUEST,
                        "Result payload schema " + payload.getSchemaRef()
                                + " does not match required schema " + requiredSchema);
            }
        }
        if (payload != null) {
            payload.setCapturedAt(new Date());
            payload.setCapturedBy(actorRef);
        }
        return payload;
    }

    private WorkItemOperationException invalidTransition(CompletionTask current,
                                                         CompletionTask.Status targetStatus) {
        return failure(WorkItemOperationException.Code.INVALID_TRANSITION,
                "Cannot transition work item from " + current.getStatus() + " to " + targetStatus);
    }

    private WorkItemOperationException failure(WorkItemOperationException.Code code, String message) {
        return new WorkItemOperationException(code, message);
    }

    private void attachGroup(String realm, CompletionTask task, String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            Optional<CompletionTaskGroup> group = groupRepo.findById(new ObjectId(groupId), realm, true);
            group.ifPresent(task::setGroup);
        }
    }

    private void markGroupRunning(String realm, String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            groupRepo.updateStatus(realm, groupId, CompletionTaskGroup.Status.RUNNING);
        }
    }

    private void notifyGroup(CompletionTask task) {
        notifyGroup(getSecurityContextRealmId(), task);
    }

    private void notifyGroup(String realm, CompletionTask task) {
        if (task.getGroup() != null) {
            String groupId = task.getGroup().getId().toString();
            groupRepo.notifyGroup(groupId, "task:" + task.getId() + ":" + task.getStatus());
            refreshGroupStatus(realm, groupId);
        }
    }

    private void refreshGroupStatus(String realm, String groupId) {
        List<CompletionTask> tasks = listByGroup(realm, groupId);
        if (tasks.isEmpty()) {
            return;
        }
        boolean allTerminal = tasks.stream().allMatch(task -> task.getStatus() != null && task.getStatus().isTerminal());
        groupRepo.updateStatus(realm, groupId,
                allTerminal ? CompletionTaskGroup.Status.COMPLETE : CompletionTaskGroup.Status.RUNNING);
    }
}
