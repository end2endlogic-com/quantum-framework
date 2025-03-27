package com.e2eq.framework.util;


import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.Getter;


import java.util.Date;


@ApplicationScoped
@Data
public class TestUtils {
   @Inject
   SecurityUtils securityUtils;

   @Getter
   protected String accountNumber;
   @Getter
   protected String orgRefName;
   @Getter
   protected String tenantId;
   @Getter
   protected String defaultRealm;
   @Getter
   protected String testRealm;
   @Getter
   protected String systemUserId;
   @Getter
   protected String email;
   @Getter
   protected String area;
   @Getter
   protected String name;
   @Getter
   protected DataDomain dataDomain;




   @PostConstruct
   public void init() {
      accountNumber = securityUtils.getTestAccountNumber();
      orgRefName = securityUtils.getTestOrgRefName();
      tenantId = securityUtils.getTestTenantId();
      defaultRealm = securityUtils.getTestRealm();
      testRealm = securityUtils.getTestRealm();
      systemUserId = securityUtils.getTestUserId();
      email = securityUtils.getTestUserId();
      area = "SECURITY";
      name = "ADMIN";
      dataDomain = new DataDomain(orgRefName, accountNumber, tenantId, 0, systemUserId);
   }




   public PrincipalContext getPrincipalContext (String userId, String[] roles) {
      PrincipalContext c =  new PrincipalContext.Builder()
                .withDataDomain(dataDomain)
                .withDefaultRealm(defaultRealm)
                .withUserId(userId)
                .withScope("Authentication")
                .withRoles(roles).build();

      return c;
   }

   public AuditInfo createAuditInfo() {
       return new AuditInfo(new Date(), systemUserId, new Date(), systemUserId);
   }

   public ResourceContext getResourceContext(String area, String functionalDomain, String action) {
      return new ResourceContext.Builder()
         .withArea(area)
         .withFunctionalDomain(functionalDomain)
         .withAction(action).build();

   }

   /** TODO Given the rule context is application scoped, perhaps it needs to be request scoped because the rules maay change
    *   if its application scoped then this initRules shoulds not be public
    * @param ruleContext
    * @param area
    * @param functionalDomain
    * @param userId
    */
   public void initDefaultRules(RuleContext ruleContext, String area, String functionalDomain, String userId) {
      SecurityURIHeader header = new SecurityURIHeader.Builder()
         .withAction("*")
         .withIdentity(userId)
         .withArea(area)
         .withFunctionalDomain(functionalDomain).build();

      SecurityURIBody body = new SecurityURIBody.Builder()
         .withOrgRefName(orgRefName)
         .withAccountNumber(accountNumber)
         .withRealm(defaultRealm)
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
