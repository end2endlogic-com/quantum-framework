package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.*;


public class SecurityUtils {
   // system context values
   public static final String anonymousUserId = "anonymous@system.com";
   public static final String systemOrgRefName = "system.com";
   public static final String systemAccountNumber = "0000000000";
   public  static final String systemTenantId = "system-com";
   public  static final String systemRealm = "system-com";
   public  static final String systemUserId = "system@system.com";
   public  static final String any = "*";
   protected  static final String securityArea = "security";
   public  static final String name = "System Process";
   public  static final String[] systemRole = {"system"};
   public static final int defaultDataSegment = 0;
   public static final String defaultRealm = "system-com";

   public static final DataDomain systemDataDomain = new DataDomain(systemOrgRefName, systemAccountNumber, systemTenantId, defaultDataSegment, systemUserId);

   public static final SecurityURIHeader systemSecurityHeader = new SecurityURIHeader.Builder()
      .withAction(any)
      .withIdentity(systemUserId)
      .withArea(securityArea)
      .withFunctionalDomain(any).build();

   public static final SecurityURIBody systemSecurityBody = new SecurityURIBody.Builder()
      .withRealm(systemRealm)
      .withOrgRefName(systemOrgRefName)
      .withAccountNumber(systemAccountNumber)
      .withTenantId(systemTenantId)
      .withOwnerId(systemUserId)
      .build();

   public static final PrincipalContext systemPrincipalContext = new PrincipalContext.Builder()
         .withDefaultRealm(systemRealm)
         .withDataDomain(systemDataDomain)
         .withUserId(systemUserId)
         .withRoles(systemRole)
         .withScope("systemGenerated")
      .build();

   public static final ResourceContext systemSecurityResourceContext = new ResourceContext.Builder()
         .withArea(securityArea)
         .withFunctionalDomain(any)
         .withAction(any)
      .build();


   //TODO: create a stack and push and pop instead of set and reset
   public static void setSecurityContext() {
      SecurityContext.setPrincipalContext(systemPrincipalContext);
      SecurityContext.setResourceContext(systemSecurityResourceContext);
   }

   public static void clearSecurityContext() {
      SecurityContext.clear();
   }

   /**
    Note this is not the applicationContext reference but a
    distinct and different one.
    @return
    */
   //TODO: like in other areas consider using a stack for the context
   public static RuleContext getSystemRuleContext() {
      RuleContext ruleContext = new RuleContext();
      ruleContext.addRule(systemSecurityHeader, new Rule.Builder()
         .withName("AllowSystemPrivileges")
         .withSecurityURI(
            new SecurityURI(systemSecurityHeader, systemSecurityBody)
         )
         .withEffect(RuleEffect.ALLOW).build());

       return ruleContext;
   }



}
