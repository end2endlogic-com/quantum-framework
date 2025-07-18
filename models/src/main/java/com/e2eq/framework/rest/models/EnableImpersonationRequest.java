package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

@Data
@RegisterForReflection
public class EnableImpersonationRequest {
   protected String username;
   protected String realmToEnableIn;
   protected String impersonationScript;
   protected String realmRegExFilter;
}
