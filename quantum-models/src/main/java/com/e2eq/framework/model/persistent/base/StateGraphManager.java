package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.annotations.StateGraph;
import com.e2eq.framework.annotations.Stateful;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.StateNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * Represents a node in the state graph
 */


/**
 * Manages state graphs and their transitions
 */
@ApplicationScoped
public class StateGraphManager {

    private final Map<String, StringState> stateGraphs = new ConcurrentHashMap<>();

    private StateGraphManager() {}


    /**
     * Defines a new state graph with specified transitions
     */
    public void defineStateGraph(@Valid StringState stringState) {
        stateGraphs.put(stringState.getFieldName(), stringState);
    }

    /**
     * Validates if a transition is allowed
     * @throws InvalidStateTransitionException if transition is invalid
     */
    public void validateTransition(@NotNull String graphName, @NotNull String currentState, @NotNull String newState)
            throws InvalidStateTransitionException {
        StringState graph = stateGraphs.get(graphName);
        if (graph == null) {
            throw new InvalidStateTransitionException("State graph '" + graphName + "' not found");
        }

        List<StateNode> nextNodes = graph.getTransitions().get(currentState);
        if (nextNodes == null) {
            throw new InvalidStateTransitionException(
                String.format("Current state %s does not have any transitions defined for new state: %s in graph:%s", currentState, newState, graphName));

        }
        if (nextNodes.stream().noneMatch(node -> node.getState().equals(newState))) {
            throw new InvalidStateTransitionException(
                String.format("Invalid transition from %s to %s in graph %s",
                    currentState, newState, graphName)
            );
        }
    }

    /**
     * Gets possible next states for a given state
     */
    public List<StateNode> getNextPossibleStates(@NotNull String graphName, @NotNull String currentState) throws InvalidStateTransitionException {
        StringState graph = stateGraphs.get(graphName);
        if (graph == null) {
            throw new InvalidStateTransitionException("State graph '" + graphName + "' not found");
        }

        List<StateNode> nodeList = graph.getTransitions().get(currentState);
        if (nodeList != null) { return nodeList; } else  throw new InvalidStateTransitionException(String.format("Node for state :%s does not exist in graph:%s", currentState,graphName));
    }

    /**
     * Validates and sets a state field on an object
     */
    public void setState(@NotNull Object entity, @NotNull String fieldName, @NotNull String newState)
            throws InvalidStateTransitionException, NoSuchFieldException, IllegalAccessException {
        if (entity.getClass().getAnnotation(Stateful.class) == null) {
            throw new IllegalArgumentException("Entity must be annotated with @Stateful");
        }

        Field field = entity.getClass().getDeclaredField(fieldName);
        StateGraph stateGraph = field.getAnnotation(StateGraph.class);

        if (stateGraph == null) {
            throw new IllegalArgumentException("Field " + fieldName + " is not annotated with @StateGraph");
        }

        field.setAccessible(true);
        String currentState = (String) field.get(entity);

        validateTransition(stateGraph.graphName(), currentState != null ? currentState : "", newState);
        field.set(entity, newState);
    }

    /**
     * Returns an unmodifiable view of the managed state graphs
     * @return Map of graph names to their state graphs
     */
    public Map<String, StringState> getStateGraphs() {
        return Collections.unmodifiableMap(stateGraphs);
    }

    /**
     * Prints the state graph starting from initial nodes, recursively following all possible paths
     * @param graphName The name of the state graph to print
     * @return A string representation of the state graph
     * @throws IllegalArgumentException if the graphName does not exist
     */
    public String printStateGraph(@NotNull String graphName) {
       StringState graph = stateGraphs.get(graphName);
        if (graph == null) {
            throw new IllegalArgumentException("State graph '" + graphName + "' not found");
        }

        // Find initial states
        // Find initial states
        List<StateNode> initialNodes;
        // iterate through the graph getStates collection and find the ones where the isInitialState is true
        initialNodes = graph.getStates().values().stream().filter(StateNode::isInitialState).collect(Collectors.toList());

        if (initialNodes.isEmpty()) {
            return "State graph '" + graphName + "' has no initial states defined.";
        }

        StringBuilder output = new StringBuilder();
        output.append("State Graph: ").append(graphName).append("\n");
        output.append("Initial States:\n");

        Set<String> visited = new HashSet<>();
        for (StateNode initialNode : initialNodes) {
            output.append("- ").append(formatState(initialNode)).append("\n");
            printStatePaths(graphName, initialNode, graph, "", visited, output, 1);
        }

        return output.toString();
    }

    /**
     * Helper method to recursively print state paths
     */
    private void printStatePaths(String graphName, StateNode currentNode, StringState graph,
                                 String indent, Set<String> visited, StringBuilder output, int depth) {
        visited.add(currentNode.getState());

        List<StateNode> nextNodes = graph.getTransitions().get(currentNode.getState());
        if (nextNodes == null || nextNodes.isEmpty()) {
            return;
        }

        for (StateNode nextNode : nextNodes) {
            output.append(indent).append("└── ").append(formatState(nextNode)).append("\n");
            if (!visited.contains(nextNode.getState()) && !nextNode.isFinalState()) {
                printStatePaths(graphName, nextNode, graph, indent + "    ", new HashSet<>(visited), output, depth + 1);
            }
        }
    }

    /**
     * Formats a state node for display
     */
    private String formatState(StateNode node) {
        StringBuilder sb = new StringBuilder(node.getState());
        if (node.isInitialState()) {
            sb.append(" [Initial]");
        }
        if (node.isFinalState()) {
            sb.append(" [Final]");
        }
        return sb.toString();
    }
}
