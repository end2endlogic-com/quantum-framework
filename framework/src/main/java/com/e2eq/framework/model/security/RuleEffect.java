package com.e2eq.framework.model.security;

public enum RuleEffect {
   ALLOW (true),
   DENY( false);

   boolean value;

   RuleEffect(boolean v)  {
      value = v;
   }

   public boolean valueOf () {
      return value;
   }
}
