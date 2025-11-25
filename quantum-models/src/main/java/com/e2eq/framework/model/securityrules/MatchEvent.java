package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode
@ToString
@RegisterForReflection
@SuperBuilder
public class MatchEvent {
    String principalUriString;
    String ruleUriString;
    String ruleName;
    boolean matched;
    String difference;
    String postScript;
    boolean postScriptResult;
    String preScript;
    boolean preScriptResult;

    // --- Additive fields for filter applicability tracing (non-breaking) ---
    String filterAndString;     // raw andFilterString from the rule (after variable substitution may differ)
    String filterOrString;      // raw orFilterString from the rule
    String filterJoinOp;        // join op when both are present (AND by default)
    boolean filterEvaluated;    // true when in-memory predicate evaluation executed
    Boolean filterResult;       // null when not evaluated; true/false when evaluated
    String filterReason;        // explanation when not evaluated or short-circuited
}
