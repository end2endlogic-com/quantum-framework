package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.security.rules.RuleContext;
import com.e2eq.framework.model.security.*;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import io.quarkus.logging.Log;
import org.bson.Document;
import org.jboss.logging.Logger;

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
   public void prePersist(Object entity, Document document, Datastore datastore) {
      EntityListener.super.prePersist(entity, document, datastore);
      // Check that we have the permission to write
      // should do the permission check here.

      if (SecurityContext.getResourceContext().isPresent()) {
         if (!(SecurityContext.getResourceContext().get().getAction().equals("save") ||
                 SecurityContext.getResourceContext().get().getAction().equals("update") ||
                 SecurityContext.getResourceContext().get().getAction().equals("*"))) {
            if (Log.isEnabled(Logger.Level.WARN))
               //  throw new RuntimeException("Configuration error, post persist called but action expected to be save or update and is:" + SecurityContext.getResourceContext().get().getAction());
               Log.warn("pre persist called but action expected to be save or update and is:" + SecurityContext.getResourceContext().get().getAction());

         }
         doCheck();
      } else {
         Log.warn("No Resource Context found there for no permission check executed for entity:" + entity.getClass().getName() + " Document:" + document.toJson());
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
            SecurityCheckResponse response = ruleContext.check(pContext, rContext);
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
