package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Entity
@Data
@RegisterForReflection
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString( callSuper = true )
@OntologyClass(id = "UserGroup")
public class UserGroup extends BaseModel {

   @OntologyProperty(id = "hasMember", ref = "UserProfile", materializeEdge = true)
   protected List<EntityReference> userProfiles;
   protected List<String> roles;

   @Override
   public String bmFunctionalArea () {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain () {
      return "USER_GROUP";
   }
}
