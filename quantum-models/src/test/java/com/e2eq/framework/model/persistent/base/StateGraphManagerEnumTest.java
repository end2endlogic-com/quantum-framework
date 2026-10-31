package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.annotations.StateGraph;
import com.e2eq.framework.annotations.Stateful;
import com.e2eq.framework.model.persistent.StateNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateGraphManagerEnumTest {

    @Test
    void readsAndSetsEnumBackedStateFields() throws Exception {
        StateGraphManager manager = newManager();
        StateNode open = node("OPEN", true, false);
        StateNode claimed = node("CLAIMED", false, false);
        Map<String, StateNode> states = new LinkedHashMap<>();
        states.put("OPEN", open);
        states.put("CLAIMED", claimed);
        manager.defineStateGraph(StringState.builder()
                .fieldName("testEnumLifecycle")
                .states(states)
                .transitions(Map.of("OPEN", List.of(claimed), "CLAIMED", List.of()))
                .build());

        EnumEntity entity = new EnumEntity();
        entity.status = Status.OPEN;
        manager.setState(entity, "status", "CLAIMED");

        assertEquals(Status.CLAIMED, entity.status);
        assertEquals("CLAIMED", StateGraphManager.stateName(entity.status));
    }

    private static StateGraphManager newManager() throws Exception {
        Constructor<StateGraphManager> constructor = StateGraphManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static StateNode node(String state, boolean initial, boolean terminal) {
        return StateNode.builder().state(state).initialState(initial).finalState(terminal).build();
    }

    enum Status {
        OPEN,
        CLAIMED
    }

    @Stateful
    static class EnumEntity {
        @StateGraph(graphName = "testEnumLifecycle")
        Status status;
    }
}
