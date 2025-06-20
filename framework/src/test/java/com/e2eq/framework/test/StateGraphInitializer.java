package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.StateNode;
import com.e2eq.framework.model.persistent.base.StateGraphManager;
import com.e2eq.framework.model.persistent.base.StringState;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Startup
@ApplicationScoped
public class StateGraphInitializer {
    @Inject
    StateGraphManager stateGraphManager;
    @PostConstruct
    void init() {

        StringState orderStringState = new StringState();
        orderStringState.setFieldName("orderStringState");
        // Define order status state graph
        Map<String, StateNode> states = new HashMap<>();
        states.put("PENDING", createStateNode("PENDING", true, false));
        states.put("PROCESSING", createStateNode("PROCESSING", false, false));
        states.put("CANCELLED", createStateNode("CANCELLED", false, true));
        states.put("SHIPPED", createStateNode("SHIPPED", false, false));
        states.put("DELIVERED", createStateNode("DELIVERED", false, true));
        orderStringState.setStates(states);

        Map<String, List<StateNode>> orderTransitions = new HashMap<>();
        orderTransitions.put("PENDING", List.of(states.get("PROCESSING"), states.get("CANCELLED")));
        orderTransitions.put("PROCESSING", List.of(states.get("SHIPPED"), states.get("CANCELLED")));
        orderTransitions.put("SHIPPED", List.of(states.get("DELIVERED"), states.get("CANCELLED")));
        orderTransitions.put("DELIVERED", null);
        orderTransitions.put("CANCELLED", null);

        orderStringState.setTransitions(orderTransitions);

        stateGraphManager.defineStateGraph(orderStringState);
    };

    private StateNode createStateNode(String state, boolean initialState, boolean finalState) {
        return StateNode.builder()
                  .state(state)
                  .initialState(initialState)
                  .finalState(finalState)
                  .build();
    }
}
