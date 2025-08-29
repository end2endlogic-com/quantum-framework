package com.e2eq.framework.model.persistent.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;

import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

@Entity("policy")
@RegisterForReflection
public class Policy extends FullBaseModel {
   protected @NotNull String principalId;
   protected String description;
   protected List<Rule> rules = new ArrayList<>();

   public String getPrincipalId () {
      return principalId;
   }

   public void setPrincipalId (String principalId) {
      this.principalId = principalId;
   }

   public String getDescription () {
      return description;
   }

   public void setDescription (String description) {
      this.description = description;
   }

   public List<Rule> getRules () {
      return rules;
   }

   public void setRules (List<Rule> rules) {
      this.rules = rules;
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "POLICY";
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof Policy)) return false;
      if (!super.equals(o)) return false;

      Policy policy = (Policy) o;

      if (principalId != null ? !principalId.equals(policy.principalId) : policy.principalId != null) return false;
      if (description != null ? !description.equals(policy.description) : policy.description != null) return false;
      return rules != null ? rules.equals(policy.rules) : policy.rules == null;
   }

   @Override
   public int hashCode () {
      int result = super.hashCode();
      result = 31 * result + (principalId != null ? principalId.hashCode() : 0);
      result = 31 * result + (description != null ? description.hashCode() : 0);
      result = 31 * result + (rules != null ? rules.hashCode() : 0);
      return result;
   }
}
