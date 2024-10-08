package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.util.SecurityUtils;

public class SecuritySession implements AutoCloseable{
   PrincipalContext pContext;
   ResourceContext rContext;

   public SecuritySession (PrincipalContext pContext, ResourceContext rContext) {
      this.pContext = pContext;
      this.rContext = rContext;
      SecurityContext.setPrincipalContext(pContext);
      SecurityContext.setResourceContext(rContext);
   }


   @Override
   public void close () {
      SecurityUtils.clearSecurityContext();
   }
}
