package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.securityrules.RuleContext;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PrePersist;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.bson.Document;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class PermissionRuleInterceptor implements EntityListener {

   @Inject
   RuleContext ruleContext;

   @ConfigProperty(name = "quantum.test.seeding", defaultValue = "false")
   boolean testSeedingBypass;

   @Override
   public boolean hasAnnotation(Class type) {
      return false;
   }


   @Override
   @PrePersist
   public void prePersist(Object entity, Document document, Datastore datastore) {
      EntityListener.super.prePersist(entity, document, datastore);
      // Check that we have the permission to write
      // should do the permission check here.

      // Test-only bypass to allow seed import without full permission wiring
      if (testSeedingBypass) {
         if (Log.isDebugEnabled()) Log.debugf("[PermissionRuleInterceptor] test seeding bypass enabled; skipping check for %s", entity.getClass().getSimpleName());
         return;
      }

      if (SecurityContext.getResourceContext().isPresent()) {
         // Bypass when a test seeder marks the operation explicitly as a seed write
         ResourceContext rc = SecurityContext.getResourceContext().get();
         if (rc.getAction() != null && rc.getAction().equalsIgnoreCase("seed")) {
            if (Log.isDebugEnabled()) Log.debugf("[PermissionRuleInterceptor] bypass for action=seed for fd:%s, id:%s", rc.getFunctionalDomain(), rc.getResourceId());
            return;
         }
         if (!(SecurityContext.getResourceContext().get().getAction().equalsIgnoreCase("save") ||
                 SecurityContext.getResourceContext().get().getAction().equalsIgnoreCase("update") ||
                 SecurityContext.getResourceContext().get().getAction().equalsIgnoreCase("delete") ||
                  SecurityContext.getResourceContext().get().getAction().equalsIgnoreCase("write") ||
                 SecurityContext.getResourceContext().get().getAction().equals("*"))) {
            if (Log.isEnabled(Logger.Level.WARN))
               //  throw new RuntimeException("Configuration error, post persist called but action expected to be save or update and is:" + SecurityContext.getResourceContext().get().getAction());
               Log.debugf("pre persist called but action expected to be save or update and is:%s" , SecurityContext.getResourceContext().get().getAction());

         }
         doCheck();
      } else {
         Log.warnf("No Resource Context found there for no permission check executed for entity:%s" , entity.getClass().getName() + " Document:" + document.toJson());
      }
   }

   void doCheck() {
      Optional<PrincipalContext> opPrincipalContext = SecurityContext.getPrincipalContext();
      Optional<ResourceContext> opResourceContext = SecurityContext.getResourceContext();
      if (opPrincipalContext.isPresent())
      {
         if (opResourceContext.isPresent()) {
            PrincipalContext pContext = opPrincipalContext.get();
            ResourceContext rContext = opResourceContext.get();
            SecurityCheckResponse response = ruleContext.checkRules(pContext, rContext);
            if (!response.getFinalEffect().equals(RuleEffect.ALLOW)) {
               Log.error(response.toString());
               throw new SecurityCheckException(response);
            }
         }
      }
   }

  /* @Override
   public void postLoad (Object ent, Document document, Mapper mapper) {
      Log.debug("Loaded Object:" + ent.getClass().getName());
      // ensure that resource actions is something we expect after a load i.e. view
      if (SecurityContext.getResourceContext().isPresent()) {
         if (!SecurityContext.getResourceContext().get().getAction().equals("view")) {
            doCheck();
         }
      }
   } */
}
