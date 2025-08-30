package com.e2eq.framework.model.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;
import jakarta.validation.constraints.Email;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Set;

@Entity()
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class Organization extends FullBaseModel {


   @Email( message = "ownerEmail must be a valid email address"  )
   protected String ownerEmail;

   @Schema(implementation = String.class, description = "collection of child orgs object ids")
   protected Set<ObjectId> children;
   @Schema(implementation = String.class, description = "collection of parent orgs object ids")
   protected Set<ObjectId> parents;

   @Override
   public void setRefName(String refName) {

      // check if the refName follows the regular expression: regexp = "^[a-zA-Z0-9]+\\.[a-zA-Z0-9]{1,3}$"
      // if not, throw an exception
      if (!refName.matches("^[a-zA-Z0-9]+\\.[a-zA-Z0-9]{1,3}$")) {
         throw new IllegalArgumentException("refName must follow the pattern 'string.min1.period.string.min1.max3'");
      }

      // check if the refName already exists in the database
      // if it does, throw an exception
      // TODO: implement database check

      // call the parent class method to set the refName

      super.setRefName(refName);
   }


   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "ORGANIZATION";
   }
}
