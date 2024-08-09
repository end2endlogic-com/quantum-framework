package com.e2eq.framework.security.model.persistent.models.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;

@Entity("organization")
@RegisterForReflection
public class Organization extends FullBaseModel {

   protected String ownerEmail;

   public Organization() {
      super();
   }

   public String getOwnerEmail () {
      return ownerEmail;
   }

   public void setOwnerEmail (String ownerEmail) {
      this.ownerEmail = ownerEmail;
   }

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "ORGANIZATION";
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof Organization)) return false;
      if (!super.equals(o)) return false;

      Organization that = (Organization) o;

      return ownerEmail != null ? ownerEmail.equals(that.ownerEmail) : that.ownerEmail == null;
   }

   @Override
   public int hashCode () {
      int result = super.hashCode();
      result = 31 * result + (ownerEmail != null ? ownerEmail.hashCode() : 0);
      return result;
   }
}
