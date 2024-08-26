package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.FunctionalDomain;

import jakarta.enterprise.context.ApplicationScoped;

import static com.e2eq.framework.model.securityrules.RuleContext.DefaultRealm;

@ApplicationScoped
public class FunctionalDomainRepo extends MorphiaRepo<FunctionalDomain>{

   @Override
   public String getDefaultRealmId () {
      return DefaultRealm;
   }
}
