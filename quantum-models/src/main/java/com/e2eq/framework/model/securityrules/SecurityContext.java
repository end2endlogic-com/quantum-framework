package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import io.quarkus.logging.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public class SecurityContext {
   private static final ThreadLocal<PrincipalContext> tlPrincipalContext = new ThreadLocal<PrincipalContext>();
   private static final ThreadLocal<ResourceContext> tlResourceContext = new ThreadLocal<ResourceContext>();

   // Stacks for push/pop of contexts - allows nested context switching
   private static final ThreadLocal<Deque<ResourceContext>> tlResourceContextStack = ThreadLocal.withInitial(ArrayDeque::new);
   private static final ThreadLocal<Deque<PrincipalContext>> tlPrincipalContextStack = ThreadLocal.withInitial(ArrayDeque::new);

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
      tlResourceContext.remove();
      if (Log.isDebugEnabled()) {
         Log.debug("=====CLEAR Resource Context ");
      }
   }

   public static void clearPrincipalContext() {
      tlPrincipalContext.remove();
      if (Log.isDebugEnabled()) {
         Log.debug("===== CLEAR Principal Context" );
      }
   }

   /**
    * Pushes the current ResourceContext onto a stack and sets a new one.
    * Use this when making internal queries that need a different context
    * (e.g., from AccessListResolvers that query different model types).
    *
    * Must be paired with {@link #popResourceContext()} in a finally block.
    *
    * @param newContext the new ResourceContext to set
    */
   public static void pushResourceContext(ResourceContext newContext) {
      ResourceContext current = tlResourceContext.get();
      if (current != null) {
         tlResourceContextStack.get().push(current);
      }
      setResourceContext(newContext);
      if (Log.isDebugEnabled()) {
         Log.debugf("===== PUSH Resource Context: %s|%s|%s (stack depth: %d)",
             newContext.getArea(), newContext.getFunctionalDomain(), newContext.getAction(),
             tlResourceContextStack.get().size());
      }
   }

   /**
    * Pops the previous ResourceContext from the stack and restores it.
    * Must be called in a finally block after {@link #pushResourceContext(ResourceContext)}.
    */
   public static void popResourceContext() {
      Deque<ResourceContext> stack = tlResourceContextStack.get();
      if (!stack.isEmpty()) {
         ResourceContext previous = stack.pop();
         tlResourceContext.set(previous);
         if (Log.isDebugEnabled()) {
            Log.debugf("===== POP Resource Context: restored %s|%s|%s (stack depth: %d)",
                previous.getArea(), previous.getFunctionalDomain(), previous.getAction(),
                stack.size());
         }
      } else {
         // No previous context to restore - just clear it
         tlResourceContext.remove();
         if (Log.isDebugEnabled()) {
            Log.debug("===== POP Resource Context: stack empty, cleared context");
         }
      }
   }

   /**
    * Pushes the current PrincipalContext onto a stack and sets a new one.
    * Use this when making internal queries that need a different context.
    *
    * Must be paired with {@link #popPrincipalContext()} in a finally block.
    *
    * @param newContext the new PrincipalContext to set
    */
   public static void pushPrincipalContext(PrincipalContext newContext) {
      PrincipalContext current = tlPrincipalContext.get();
      if (current != null) {
         tlPrincipalContextStack.get().push(current);
      }
      setPrincipalContext(newContext);
      if (Log.isDebugEnabled()) {
         Log.debugf("===== PUSH Principal Context: %s|%s (stack depth: %d)",
             newContext.getDefaultRealm(), newContext.getUserId(),
             tlPrincipalContextStack.get().size());
      }
   }

   /**
    * Pops the previous PrincipalContext from the stack and restores it.
    * Must be called in a finally block after {@link #pushPrincipalContext(PrincipalContext)}.
    */
   public static void popPrincipalContext() {
      Deque<PrincipalContext> stack = tlPrincipalContextStack.get();
      if (!stack.isEmpty()) {
         PrincipalContext previous = stack.pop();
         tlPrincipalContext.set(previous);
         if (Log.isDebugEnabled()) {
            Log.debugf("===== POP Principal Context: restored %s|%s (stack depth: %d)",
                previous.getDefaultRealm(), previous.getUserId(),
                stack.size());
         }
      } else {
         // No previous context to restore - just clear it
         tlPrincipalContext.remove();
         if (Log.isDebugEnabled()) {
            Log.debug("===== POP Principal Context: stack empty, cleared context");
         }
      }
   }

   /*
   ** we want the variable to stay around just the container is what we are changing.
   **
   public static void clearSecurityURIHeader() {
      tlSecurityURIHeader.remove();
   } */
}
