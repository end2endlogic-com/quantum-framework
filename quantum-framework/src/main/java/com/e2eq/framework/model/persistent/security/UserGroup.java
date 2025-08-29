package com.e2eq.framework.model.persistent.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.rest.models.Role;
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
public class UserGroup extends BaseModel {

   protected List<EntityReference> userProfiles;
   protected List<Role> roles;

   @Override
   public String bmFunctionalArea () {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain () {
      return "USER_GROUP";
   }
}
