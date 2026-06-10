package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo;
import com.e2eq.framework.model.security.RealmTenantMembership;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import io.quarkus.arc.properties.IfBuildProperty;

@Path("/security/realm-memberships")
@RolesAllowed({ "admin", "system" })
@Tag(name = "security", description = "Operations related to security")
@IfBuildProperty(name = "quantum.system-rest.enabled", stringValue = "true", enableIfMissing = true) // control-plane admin surface; one-switch opt-out (CONTROL_PLANE_SPLIT_DESIGN.md Phase B, wp3 tier 1)
public class RealmTenantMembershipResource extends BaseResource<RealmTenantMembership, RealmTenantMembershipRepo> {
   protected RealmTenantMembershipResource(RealmTenantMembershipRepo repo) {
      super(repo);
   }
}
