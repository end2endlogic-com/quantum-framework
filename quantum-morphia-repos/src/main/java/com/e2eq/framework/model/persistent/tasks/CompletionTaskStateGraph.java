package com.e2eq.framework.model.persistent.tasks;

import com.e2eq.framework.model.persistent.StateNode;
import com.e2eq.framework.model.persistent.base.StateGraphManager;
import com.e2eq.framework.model.persistent.base.StringState;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the governed work-item lifecycle while retaining transitions used
 * by legacy completion-task clients.
 */
@Startup
@ApplicationScoped
public class CompletionTaskStateGraph {
    public static final String GRAPH_NAME = "completionTaskLifecycle";

    @Inject
    StateGraphManager stateGraphManager;

    @PostConstruct
    void register() {
        Map<String, StateNode> states = new LinkedHashMap<>();
        state(states, CompletionTask.Status.PENDING, true, false);
        state(states, CompletionTask.Status.RUNNING, false, false);
        state(states, CompletionTask.Status.SUCCESS, false, true);
        state(states, CompletionTask.Status.FAILED, false, true);
        state(states, CompletionTask.Status.OPEN, true, false);
        state(states, CompletionTask.Status.CLAIMED, false, false);
        state(states, CompletionTask.Status.IN_PROGRESS, false, false);
        state(states, CompletionTask.Status.COMPLETED, false, true);
        state(states, CompletionTask.Status.CANCELLED, false, true);
        state(states, CompletionTask.Status.EXPIRED, false, true);

        Map<String, List<StateNode>> transitions = new LinkedHashMap<>();
        transitions.put(name(CompletionTask.Status.PENDING), nodes(states,
                CompletionTask.Status.CLAIMED, CompletionTask.Status.RUNNING,
                CompletionTask.Status.SUCCESS, CompletionTask.Status.FAILED));
        transitions.put(name(CompletionTask.Status.RUNNING), nodes(states,
                CompletionTask.Status.SUCCESS, CompletionTask.Status.FAILED));
        transitions.put(name(CompletionTask.Status.OPEN), nodes(states,
                CompletionTask.Status.CLAIMED, CompletionTask.Status.SUCCESS,
                CompletionTask.Status.COMPLETED, CompletionTask.Status.FAILED, CompletionTask.Status.CANCELLED,
                CompletionTask.Status.EXPIRED));
        transitions.put(name(CompletionTask.Status.CLAIMED), nodes(states,
                CompletionTask.Status.OPEN, CompletionTask.Status.IN_PROGRESS,
                CompletionTask.Status.SUCCESS, CompletionTask.Status.COMPLETED,
                CompletionTask.Status.FAILED, CompletionTask.Status.CANCELLED,
                CompletionTask.Status.EXPIRED));
        transitions.put(name(CompletionTask.Status.IN_PROGRESS), nodes(states,
                CompletionTask.Status.OPEN, CompletionTask.Status.SUCCESS,
                CompletionTask.Status.COMPLETED, CompletionTask.Status.FAILED,
                CompletionTask.Status.CANCELLED, CompletionTask.Status.EXPIRED));
        transitions.put(name(CompletionTask.Status.SUCCESS), List.of());
        transitions.put(name(CompletionTask.Status.FAILED), List.of());
        // Participant TODOs may be reopened. Process-task completion remains terminal
        // because its repository path never exposes REOPEN.
        transitions.put(name(CompletionTask.Status.COMPLETED), nodes(states, CompletionTask.Status.OPEN));
        transitions.put(name(CompletionTask.Status.CANCELLED), List.of());
        transitions.put(name(CompletionTask.Status.EXPIRED), List.of());

        stateGraphManager.defineStateGraph(StringState.builder()
                .fieldName(GRAPH_NAME)
                .states(states)
                .transitions(transitions)
                .build());
    }

    private static void state(Map<String, StateNode> states,
                              CompletionTask.Status status,
                              boolean initial,
                              boolean terminal) {
        states.put(name(status), StateNode.builder()
                .state(name(status))
                .initialState(initial)
                .finalState(terminal)
                .build());
    }

    private static List<StateNode> nodes(Map<String, StateNode> states, CompletionTask.Status... statuses) {
        return java.util.Arrays.stream(statuses).map(status -> states.get(name(status))).toList();
    }

    private static String name(CompletionTask.Status status) {
        return status.name();
    }
}
