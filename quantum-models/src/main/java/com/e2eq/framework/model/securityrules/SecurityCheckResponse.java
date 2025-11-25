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

   // New decision fields (additive)
   // Mirrors finalEffect for backward compatibility but provides a canonical top-level decision value
   protected String decision; // "ALLOW" | "DENY"
   // Scope of decision: EXACT (fully evaluated), SCOPED (conditionally applies; constraints enumerated), DEFAULT (fell back to default)
   protected String decisionScope; // "EXACT" | "SCOPED" | "DEFAULT"
   // Echo of the evaluation mode actually used by the server for this check (LEGACY, AUTO, STRICT)
   protected String evalModeUsed;

   // When decisionScope=SCOPED, these provide details
   protected boolean scopedConstraintsPresent = false;
   protected List<ScopedConstraint> scopedConstraints = new ArrayList<>();

   // When decisionScope=DEFAULT, include NA label for clarity and migration: NA-ALLOW or NA-DENY
   protected String naLabel;

   // Winning rule metadata (additive)
   // For EXACT decisions: the rule that determined the final effect.
   // For SCOPED decisions: the selected candidate rule that matched by URI/priority but whose constraints weren't fully evaluated.
   // For DEFAULT: remains null.
   protected String winningRuleName;
   protected Integer winningRulePriority;
   protected Boolean winningRuleFinal;

   // Indicates that the ALLOW/DENY outcome may still be constrained by rule filters that
   // were not evaluated in-memory (e.g., LIST action without a concrete resource instance).
   protected boolean filterConstraintsPresent = false;

   // Optional details of rule-level filters that apply to this check when the evaluator
   // did not run (e.g., LIST without resource). Additive shape for clients that care.
   protected List<RuleFilterInfo> filterConstraints = new ArrayList<>();

   // New: explicit section to expose rules deemed NOT_APPLICABLE (NA) with reasons
   protected List<NotApplicableInfo> notApplicable = new ArrayList<>();

   @Data
   @EqualsAndHashCode
   @ToString
   public static class RuleFilterInfo {
      private String ruleName;
      private String andFilterString;
      private String orFilterString;
      private String joinOp;

      public RuleFilterInfo() {}

      public RuleFilterInfo(String ruleName, String andFilterString, String orFilterString, String joinOp) {
         this.ruleName = ruleName;
         this.andFilterString = andFilterString;
         this.orFilterString = orFilterString;
         this.joinOp = joinOp;
      }
   }

   @Data
   @EqualsAndHashCode
   @ToString
   public static class NotApplicableInfo {
      private String ruleName;
      private String phase;   // PRECONDITION, FILTER, POSTCONDITION, OTHER
      private String reason;  // human-friendly explanation

      public NotApplicableInfo() {}

      public NotApplicableInfo(String ruleName, String phase, String reason) {
         this.ruleName = ruleName;
         this.phase = phase;
         this.reason = reason;
      }
   }

   @Data
   @EqualsAndHashCode
   @ToString
   public static class ScopedConstraint {
      private String type;   // FILTER | SCRIPT
      private String detail; // filter string or script text/name
      private String joinOp; // optional, for combined filters

      public ScopedConstraint() {}

      public ScopedConstraint(String type, String detail, String joinOp) {
         this.type = type;
         this.detail = detail;
         this.joinOp = joinOp;
      }
   }
}
