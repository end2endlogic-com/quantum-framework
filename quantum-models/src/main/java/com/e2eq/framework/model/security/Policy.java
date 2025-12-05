package com.e2eq.framework.model.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Entity("policy")
@RegisterForReflection
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Policy extends FullBaseModel {
   public enum PrincipalType {
      ROLE,
      USER;
      String value;
      PrincipalType() {
         this.value = this.name();
      }
      public String toString() {
         return this.value;
      }
   }

   protected @NotNull String principalId;
   protected PrincipalType principalType = PrincipalType.ROLE;
   protected String description;
   protected List<Rule> rules = new ArrayList<>();

   // Transient field to track policy source
   protected transient String policySource;


   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "POLICY";
   }


}
