package com.e2eq.framework.security.model.persistent.models.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Entity("functionalDomain")
@RegisterForReflection
@Data
@EqualsAndHashCode (callSuper = true)
public class FunctionalDomain extends BaseModel {

   protected String area;

   protected List<FunctionalAction> functionalActions;

   @Override
   public String bmFunctionalDomain() {
      return "FUNCTIONAL_DOMAIN";
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }


}
