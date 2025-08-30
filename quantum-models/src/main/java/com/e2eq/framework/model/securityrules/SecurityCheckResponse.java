package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.security.Rule;

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
   public SecurityCheckResponse(PrincipalContext principalContext,  ResourceContext resourceContext) {
      this.principalContext = principalContext;
      this.resourceContext = resourceContext;
   }

   protected PrincipalContext principalContext;
   protected ResourceContext resourceContext;
   protected List<MatchEvent> matchEvents = new ArrayList<>();
   protected List<SecurityURI> applicableSecurityURIs = new ArrayList<>();
   protected List<Rule> evaluatedRules = new ArrayList<>();
   protected List<RuleResult> matchedRuleResults = new ArrayList<>();
   protected RuleEffect finalEffect;
}
