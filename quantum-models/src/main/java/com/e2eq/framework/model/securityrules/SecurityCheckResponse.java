package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.security.Rule;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.EnumSet;
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
      // derive roleAssignments by default from principalContext roles as CREDENTIAL when available
      if (this.principalContext != null && this.principalContext.getRoles() != null) {
         for (String r : this.principalContext.getRoles()) {
            this.roleAssignments.add(new RoleAssignment(r, EnumSet.of(RoleSource.CREDENTIAL)));
         }
      }
   }

   protected PrincipalContext principalContext;
   protected ResourceContext resourceContext;
   // Structured mapping from roles to their assignment sources (usergroup, idp, credential)
   protected List<RoleAssignment> roleAssignments = new ArrayList<>();
   protected List<MatchEvent> matchEvents = new ArrayList<>();
   protected List<SecurityURI> applicableSecurityURIs = new ArrayList<>();
   protected List<Rule> evaluatedRules = new ArrayList<>();
   protected List<RuleResult> matchedRuleResults = new ArrayList<>();
   protected RuleEffect finalEffect;
}
