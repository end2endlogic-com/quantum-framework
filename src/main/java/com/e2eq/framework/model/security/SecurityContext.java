package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import io.quarkus.logging.Log;

import java.util.Optional;

public class SecurityContext {
   private static final ThreadLocal<PrincipalContext> tlPrincipalContext = new ThreadLocal<PrincipalContext>();
   private static final ThreadLocal<ResourceContext> tlResourceContext = new ThreadLocal<ResourceContext>();

   public static Optional<PrincipalContext> getPrincipalContext() {
      return Optional.ofNullable(tlPrincipalContext.get());
   }

   public static Optional<DataDomain> getPrincipalDataDomain() {
      if (getPrincipalContext().isPresent()) {
         DataDomain dd = new DataDomain();
         dd.setOrgRefName(SecurityContext.getPrincipalContext().get().getDataDomain().getOrgRefName());
         dd.setAccountNum(SecurityContext.getPrincipalContext().get().getDataDomain().getAccountNum());
         dd.setTenantId(SecurityContext.getPrincipalContext().get().getDataDomain().getTenantId());
         dd.setOwnerId(SecurityContext.getPrincipalContext().get().getUserId());
         dd.setDataSegment(SecurityContext.getPrincipalContext().get().getDataDomain().getDataSegment());
         return Optional.of(dd);
      } else {
         return Optional.empty();
      }

   }

   public static void setPrincipalContext(PrincipalContext pContext) {
      if (pContext == null) {
         throw new IllegalArgumentException("PContext can not be null");
      }
      if (pContext.getDataDomain() == null) {
         throw new IllegalArgumentException("DataDomain can not be null");
      }

      if (pContext.getUserId() == null || pContext.getUserId().isEmpty()) {
         throw new IllegalArgumentException("user id can not be null");
      }

      if (pContext.getDefaultRealm() == null || pContext.getDefaultRealm().isEmpty()) {
         throw new IllegalArgumentException("Default Realm can not be null");
      }

      if (pContext.getScope() == null || pContext.getScope().isEmpty()) {
         throw new IllegalArgumentException("Scope can not be null");
      }

      tlPrincipalContext.set(pContext);
      if (Log.isDebugEnabled()) {
         Log.debug("##### Principal Context Set-" + pContext.defaultRealm + "|" + pContext.getUserId() +"|" + pContext.getDataDomain().getTenantId());
      }
   }

   public static Optional<ResourceContext> getResourceContext() {
      return Optional.ofNullable(tlResourceContext.get());
   }

   public static void setResourceContext(ResourceContext rContext) {
      if (rContext == null) {
         throw new IllegalArgumentException("RContext can not be null");
      }

      if (rContext.getFunctionalDomain()== null || rContext.getFunctionalDomain().isEmpty()) {
         throw new IllegalArgumentException("Functional domain can not be null or empty");
      }

      if (rContext.getArea() == null || rContext.getArea().isEmpty()) {
         throw new IllegalArgumentException("Area can not be null or empty");
      }

      if (rContext.getAction() == null || rContext.getAction().isEmpty()) {
         throw new IllegalArgumentException("Action can not be null or empty");
      }
      tlResourceContext.set(rContext);
      if (Log.isDebugEnabled()) {
         Log.debug("===== Resource Context set:" + rContext.getArea() + "|" + rContext.getFunctionalDomain() + "|" + rContext.getAction());
      }
   }

   public static void clear() {
      clearResourceContext();
      clearPrincipalContext();
   }

   public static void clearResourceContext() {
      tlResourceContext.set(null);  if (Log.isDebugEnabled()) {
         Log.debug("=====CLEAR Resource Context ");
      }
   }

   public static void clearPrincipalContext() {
      tlPrincipalContext.set(null); if (Log.isDebugEnabled()) {
         Log.debug("===== CLEAR Principal Context" );
      }
   }

   /*
   ** we want the variable to stay around just the container is what we are changing.
   **
   public static void clearSecurityURIHeader() {
      tlSecurityURIHeader.remove();
   } */
}
