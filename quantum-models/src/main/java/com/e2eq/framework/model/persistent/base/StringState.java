package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.model.persistent.StateNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
public class StringState {
   @NotNull
   @NonNull
   String fieldName;
   Map<String, StateNode> states;
   Map<String, List<StateNode>> transitions;
}
