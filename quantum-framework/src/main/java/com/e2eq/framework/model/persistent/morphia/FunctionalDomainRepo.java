package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.FunctionalDomain;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;



@ApplicationScoped
public class FunctionalDomainRepo extends MorphiaRepo<FunctionalDomain>{
   @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
   String defaultRealm;
   @Override
   public String getSecurityContextRealmId() {
      return defaultRealm;
   }
}
