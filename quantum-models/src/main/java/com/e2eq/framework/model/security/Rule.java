package com.e2eq.framework.model.security;

import com.e2eq.framework.model.securityrules.FilterJoinOp;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;


@RegisterForReflection
public class Rule  {

   protected int id=-1;
   protected @NotNull String name;
   protected String description;
   protected @Valid @NotNull SecurityURI securityURI;
   protected String preconditionScript;
   protected String postconditionScript;

   // set this if you want to add to the set of filters that are anded together
   protected String andFilterString;

   // set this if you want to add to the set of filters that are ored together
   String orFilterString;

   // if you need to join the anded filters and the ored filters together using a join operator set this
   FilterJoinOp joinOp;

   // the rules effect
   @NotNull RuleEffect effect;

   // the rules priority
   int priority;

   // if this rule is executed should we continue to execute rules after it or stop here
   boolean finalRule;

   public static int DEFAULT_PRIORITY = 10;

   public Rule() {
       super();
    }

   public Rule(@NotNull String name, String description, @NotNull @Valid SecurityURI securityURI,
               String preconditionScript, String postconditionScript, @NotNull RuleEffect effect,
               int priority, boolean finalRule, String andFilterString, String orFilterString, FilterJoinOp joinOp) {
      super();


      this.id = id;
      this.name = name;
      this.description = description;
      this.securityURI = securityURI;
      this.preconditionScript = preconditionScript;
      this.postconditionScript = postconditionScript;
      this.effect = effect;
      this.priority = priority;
      this.finalRule = finalRule;
      this.andFilterString = andFilterString;
      this.orFilterString = orFilterString;
      this.joinOp = joinOp;
   }


   public static class Builder {
      @NotNull String name;
      String description;
      @NotNull SecurityURI securityURI;
      String preconditionScript;
      String postconditionScript;
      String andFilterString;
      String orFilterString;
      FilterJoinOp joinOp;
      @NotNull RuleEffect effect;
      int priority = DEFAULT_PRIORITY;
      boolean finalRule = false;


     public Builder withName(String name ) {
         this.name = name;
         return this;
      }

      public Builder withDescription(String description) {
         this.description = description;
         return this;
      }

      public Builder withSecurityURI(@Valid SecurityURI uri) {
         this.securityURI = uri;
         return this;
      }

      public Builder withPreconditionScript(String script) {
         this.preconditionScript = script;
         return this;
      }

      public Builder withPostconditionScript(String script) {
         this.postconditionScript = script;
         return this;
      }

      public Builder withEffect(RuleEffect effect) {
         this.effect = effect;
         return this;
      }

      public Builder withPriority(int p) {
         this.priority = p;
         return this;
      }

      public Builder withFinalRule(boolean f) {
         this.finalRule = f;
         return this;
      }

      public Builder withAndFilterString(String filterString) {
        this.andFilterString = filterString;
        return this;
      }

      public Builder withOrFilterString(String orFilterString) {
        this.orFilterString = orFilterString;
        return this;
      }

      public Builder withJoinOp(FilterJoinOp op){
        this.joinOp = op;
        return this;
      }

      public Rule build() {
         return new Rule( name, description,
            securityURI, preconditionScript,
            postconditionScript, effect, priority, finalRule,
            andFilterString, orFilterString, joinOp);
      }
   }



   public String getName () {
      return name;
   }

   public void setName (String name) {
      this.name = name;
   }

   public String getDescription () {
      return description;
   }

   public void setDescription (String description) {
      this.description = description;
   }

   public SecurityURI getSecurityURI () {
      return securityURI;
   }

   public void setSecurityURI (SecurityURI securityURI) {
      this.securityURI = securityURI;
   }

   public String getPreconditionScript () {
      return preconditionScript;
   }

   public void setPreconditionScript (String preconditionScript) {
      this.preconditionScript = preconditionScript;
   }

   public String getPostconditionScript () {
      return postconditionScript;
   }

   public void setPostconditionScript (String postconditionScript) {
      this.postconditionScript = postconditionScript;
   }

   public int getPriority () {
      return priority;
   }

   public void setPriority (int priority) {
      this.priority = priority;
   }

   public boolean isFinalRule () {
      return finalRule;
   }

   public void setFinalRule (boolean finalRule) {
      this.finalRule = finalRule;
   }

   public RuleEffect getEffect () {
      return effect;
   }

   public void setEffect (RuleEffect effect) {
      this.effect = effect;
   }

   public String getAndFilterString () {
      return andFilterString;
   }

   public void setAndFilterString (String andFilterString) {
      this.andFilterString = andFilterString;
   }

   public String getOrFilterString () {
      return orFilterString;
   }

   public void setOrFilterString (String orFilterString) {
      this.orFilterString = orFilterString;
   }

   public FilterJoinOp getJoinOp () {
      return joinOp;
   }

   public void setJoinOp (FilterJoinOp joinOp) {
      this.joinOp = joinOp;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof Rule)) return false;

      Rule rule = (Rule) o;

      if (priority != rule.priority) return false;
      if (finalRule != rule.finalRule) return false;
      if (name != null ? !name.equals(rule.name) : rule.name != null) return false;
      if (description != null ? !description.equals(rule.description) : rule.description != null) return false;
      if (securityURI != null ? !securityURI.equals(rule.securityURI) : rule.securityURI != null) return false;
      if (preconditionScript != null ? !preconditionScript.equals(rule.preconditionScript) :
             rule.preconditionScript != null)
         return false;
      if (postconditionScript != null ? !postconditionScript.equals(rule.postconditionScript) :
             rule.postconditionScript != null)
         return false;
      if (andFilterString != null ? !andFilterString.equals(rule.andFilterString) : rule.andFilterString != null)
         return false;
      if (orFilterString != null ? !orFilterString.equals(rule.orFilterString) : rule.orFilterString != null)
         return false;
      if (joinOp != rule.joinOp) return false;
      return effect == rule.effect;
   }

   @Override
   public int hashCode () {
      int result = name != null ? name.hashCode() : 0;
      result = 31 * result + (description != null ? description.hashCode() : 0);
      result = 31 * result + (securityURI != null ? securityURI.hashCode() : 0);
      result = 31 * result + (preconditionScript != null ? preconditionScript.hashCode() : 0);
      result = 31 * result + (postconditionScript != null ? postconditionScript.hashCode() : 0);
      result = 31 * result + (andFilterString != null ? andFilterString.hashCode() : 0);
      result = 31 * result + (orFilterString != null ? orFilterString.hashCode() : 0);
      result = 31 * result + (joinOp != null ? joinOp.hashCode() : 0);
      result = 31 * result + (effect != null ? effect.hashCode() : 0);
      result = 31 * result + priority;
      result = 31 * result + (finalRule ? 1 : 0);
      return result;
   }

   @Override
   public String toString () {
      return "Rule{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", securityURI=" + securityURI +
                ", preconditionScript='" + preconditionScript + '\'' +
                ", postconditionScript='" + postconditionScript + '\'' +
                ", andFilterString='" + andFilterString + '\'' +
                ", orFilterString='" + orFilterString + '\'' +
                ", joinOp=" + joinOp +
                ", effect=" + effect +
                ", priority=" + priority +
                ", finalRule=" + finalRule +
                '}';
   }
}
