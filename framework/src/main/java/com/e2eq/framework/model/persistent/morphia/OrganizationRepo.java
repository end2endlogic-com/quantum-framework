package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.Organization;
import com.e2eq.framework.model.persistent.base.DataDomain;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@ApplicationScoped
public class OrganizationRepo extends MorphiaRepo<Organization> {

   public Organization createOrganization(@NotNull String displayName, @NotNull String refName, @Valid @NotNull DataDomain dataDomain) {
      Organization org = new Organization();
      org.setDataDomain(dataDomain);
      org.setDisplayName(displayName);
      org.setRefName(refName);
      return save(org);
   }

}
