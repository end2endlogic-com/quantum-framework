package com.e2eq.framework.model.security;

import com.e2eq.framework.annotations.CanonicalRefName;
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
@CanonicalRefName(legacy = {"tenant-admin-users"})
public class UserGroup extends BaseModel {

   /** Application in which the group's roles are effective; {@code *} means every application. */
   protected String applicationId;

   @OntologyProperty(id = "hasMember", ref = "UserProfile", materializeEdge = true)
   protected List<EntityReference> userProfiles;
   protected List<String> roles;

   /**
    * The tenant that owns the group. An application binding ({@link UserAppRole}) of the group
    * grants exactly this tenant, may be written only by someone with authority over it, and applies
    * only to members with an active membership in it. Unset (groups written before the field): only
    * the platform may bind the group.
    */
   protected String ownerTenantId;

   @Override
   public void validate () {
      super.validate();
      UserRealmRole.requireNoExternalRole(roles, "UserGroup");
   }

   @Override
   public String bmFunctionalArea () {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain () {
      return "USER_GROUP";
   }
}
