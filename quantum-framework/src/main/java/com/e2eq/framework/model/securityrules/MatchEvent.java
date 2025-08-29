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
}
