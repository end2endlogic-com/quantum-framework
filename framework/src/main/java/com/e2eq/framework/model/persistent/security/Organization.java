package com.e2eq.framework.model.persistent.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.FullBaseModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;

import java.util.Set;

@Entity()
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class Organization extends FullBaseModel {

   protected String ownerEmail;

   protected Set<ObjectId> children;
   protected Set<ObjectId> parents;


   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "ORGANIZATION";
   }
}
