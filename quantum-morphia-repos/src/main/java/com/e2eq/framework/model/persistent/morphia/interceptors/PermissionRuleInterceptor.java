package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.security.runtime.RuleContext;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PrePersist;
import io.quarkus.logging.Log;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class PermissionRuleInterceptor implements EntityListener {

   @Inject
   RuleContext ruleContext;

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

       if (SecurityContext.isIgnoringRules()) {
         if (Log.isDebugEnabled()) {
            Log.debugf("[PermissionRuleInterceptor] ignore-rules mode enabled; skipping check for %s", entity.getClass().getSimpleName());
         }
         return;
      }

      if (SecurityContext.getResourceContext().isPresent()) {
          ResourceContext rc = SecurityContext.getResourceContext().get();
          String action = rc.getAction();
           if (action == null || !(action.equalsIgnoreCase("create")
                   || action.equalsIgnoreCase("save")
                   || action.equalsIgnoreCase("update")
                   || action.equalsIgnoreCase("delete")
                   || action.equalsIgnoreCase("apply")
                   || action.equalsIgnoreCase("write")
                  || action.equals("*"))) {
             throw new SecurityException("Persistence callback requires an explicit write action; received: " + action);
          }
          doCheck();
       } else {
          throw new SecurityException("Persistence callback has no ResourceContext for entity "
                  + entity.getClass().getName() + "; use SecurityCallScope for privileged internal writes");
       }
   }

   void doCheck() {
      Optional<PrincipalContext> opPrincipalContext = SecurityContext.getPrincipalContext();
      Optional<ResourceContext> opResourceContext = SecurityContext.getResourceContext();
       if (opPrincipalContext.isEmpty() || opResourceContext.isEmpty()) {
          throw new SecurityException("Permission evaluation requires explicit principal and resource contexts");
       }
       PrincipalContext pContext = opPrincipalContext.get();
       ResourceContext rContext = opResourceContext.get();
       SecurityCheckResponse response = ruleContext.checkRules(pContext, rContext);
       if (!response.getFinalEffect().equals(RuleEffect.ALLOW)) {
          Log.error(response.toString());
          throw new SecurityCheckException(response);
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
