package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.general.FeatureFlag;
import com.e2eq.framework.model.persistent.morphia.FeatureFlagRepo;
import com.e2eq.framework.rest.core.BaseResource;
import jakarta.ws.rs.Path;

@Path("/features/flags")
public class FeatureFlagResource extends BaseResource<FeatureFlag, FeatureFlagRepo> {
   protected FeatureFlagResource (FeatureFlagRepo repo) {
      super(repo);
   }
}
