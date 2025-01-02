package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.*;

import java.util.Date;


public class TestUtils {
   public static final String accountNumber = SecurityUtils.systemAccountNumber;
   public static final String orgRefName = SecurityUtils.systemOrgRefName;
   public static final String tenantId = SecurityUtils.systemTenantId;
   public static final String defaultRealm = SecurityUtils.systemRealm;
   public static final String systemUserId = SecurityUtils.systemUserId;
   public static final String email = SecurityUtils.systemUserId;
   public static final String area = "security";
   public static final String name = "Admin";
   public static final DataDomain dataDomain = new DataDomain(orgRefName, accountNumber, tenantId, 0, systemUserId);

   public static PrincipalContext getPrincipalContext (String userId, String[] roles) {
      PrincipalContext c =  new PrincipalContext.Builder()
                .withDataDomain(dataDomain)
                .withDefaultRealm(defaultRealm)
                .withUserId(userId)
                .withScope("Authentication")
                .withRoles(roles).build();

      return c;
   }

   public static AuditInfo createAuditInfo() {
       return new AuditInfo(new Date(), systemUserId, new Date(), systemUserId);
   }

   public static ResourceContext getResourceContext(String area, String functionalDomain, String action) {
      return new ResourceContext.Builder()
         .withArea(area)
         .withFunctionalDomain(functionalDomain)
         .withAction(action).build();

   }
   public static void initRules (RuleContext ruleContext, String area, String functionalDomain, String userId) {
      SecurityURIHeader header = new SecurityURIHeader.Builder()
         .withAction("*")
         .withIdentity(userId)
         .withArea(area)
         .withFunctionalDomain(functionalDomain).build();

      SecurityURIBody body = new SecurityURIBody.Builder()
         .withOrgRefName(TestUtils.orgRefName)
         .withAccountNumber(TestUtils.accountNumber)
         .withRealm(TestUtils.defaultRealm)
         .withOwnerId(userId)
         .withTenantId(tenantId).build();


      ruleContext.addRule(header,
         new Rule.Builder()
            .withName("allow any")
            .withSecurityURI(
               new SecurityURI(header, body)
            )
            .withEffect(RuleEffect.ALLOW).build());

      header = header.clone();
      header.setAction("view");
      ruleContext.addRule(header,
         new Rule.Builder()
            .withName("allow view")
            .withSecurityURI(
               new SecurityURI(header, body.clone())
            )
            .withEffect(RuleEffect.ALLOW).build());
   }



   public static void clearSecurityContext() {
      SecurityContext.setPrincipalContext(null);
      SecurityContext.setResourceContext(null);
   }
}
