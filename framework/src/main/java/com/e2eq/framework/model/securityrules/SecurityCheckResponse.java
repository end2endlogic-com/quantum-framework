package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.security.Rule;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@Data
@EqualsAndHashCode
@NoArgsConstructor
@ToString
public class SecurityCheckResponse {
   ResourceContext resourceContext;
   List<MatchEvent> matchEvents = new ArrayList<>();
   PrincipalContext principalContext;
   RuleEffect finalEffect;
   List<SecurityURI> applicableSecurityURIs = new ArrayList<>();
   List<Rule> evaluatedRules = new ArrayList<>();
   List<RuleResult> matchedRuleResults = new ArrayList<>();
}
