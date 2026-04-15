package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo;
import com.e2eq.framework.model.security.RealmTenantMembership;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/security/realm-memberships")
@RolesAllowed({ "admin", "system" })
@Tag(name = "security", description = "Operations related to security")
public class RealmTenantMembershipResource extends BaseResource<RealmTenantMembership, RealmTenantMembershipRepo> {
   protected RealmTenantMembershipResource(RealmTenantMembershipRepo repo) {
      super(repo);
   }
}
