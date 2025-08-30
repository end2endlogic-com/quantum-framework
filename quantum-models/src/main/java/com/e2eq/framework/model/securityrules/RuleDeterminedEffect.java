package com.e2eq.framework.model.securityrules;

public enum RuleDeterminedEffect {
   ALLOW,
   DENY,
   NOT_APPLICABLE;

   public static RuleDeterminedEffect valueOf(RuleEffect effect) {
      RuleDeterminedEffect rc;
      if (effect == RuleEffect.ALLOW) {
         rc = ALLOW;
      } else {
         rc = DENY;
      }
      return rc;
   }
}
