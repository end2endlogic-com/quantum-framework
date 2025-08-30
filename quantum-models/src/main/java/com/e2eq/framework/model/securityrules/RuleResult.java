package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.security.Rule;
import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.constraints.NotNull;

@RegisterForReflection
public class RuleResult {
   protected Rule rule;
   protected RuleDeterminedEffect determinedEffect = RuleDeterminedEffect.NOT_APPLICABLE;

   public RuleResult(@NotNull Rule r) {
      rule = r;
   }

   public Rule getRule () {
      return rule;
   }
   public void setRule (@NotNull Rule rule) {
      this.rule = rule;
   }

   public void setDeterminedEffect (RuleDeterminedEffect result) {
      this.determinedEffect = result;
   }

   public RuleDeterminedEffect getDeterminedEffect () {
      return determinedEffect;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof RuleResult)) return false;

      RuleResult that = (RuleResult) o;

      if (rule != null ? !rule.equals(that.rule) : that.rule != null) return false;
      return determinedEffect == that.determinedEffect;
   }

   @Override
   public int hashCode () {
      int result = rule != null ? rule.hashCode() : 0;
      result = 31 * result + (determinedEffect != null ? determinedEffect.hashCode() : 0);
      return result;
   }
}
