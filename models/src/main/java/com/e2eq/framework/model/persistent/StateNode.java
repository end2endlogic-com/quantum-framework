package com.e2eq.framework.model.persistent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Data
@SuperBuilder
public class StateNode {
    private final String state;
    private final Set<StateNode> nextStates = new HashSet<>();
    private boolean finalState;
    private boolean initialState;

    public void addTransition(StateNode nextState) {
        nextStates.add(nextState);
    }

    @Override
    public String toString() {
        return String.format("State:%s, Transitions[%s]",state, Arrays.toString(nextStates.stream().map(StateNode::getState).toArray()));
    }

    @Override
    public final boolean equals (Object o) {
        if (!(o instanceof StateNode stateNode)) return false;

       return finalState == stateNode.finalState && initialState == stateNode.initialState && state.equals(stateNode.state) && Objects.equals(nextStates, stateNode.nextStates);
    }

    @Override
    public int hashCode () {
        int result = state.hashCode();
        result = 31 * result + Objects.hashCode(nextStates);
        result = 31 * result + Boolean.hashCode(finalState);
        result = 31 * result + Boolean.hashCode(initialState);
        return result;
    }
}
