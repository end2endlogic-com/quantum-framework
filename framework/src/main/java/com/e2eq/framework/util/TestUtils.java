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
   protected String testAccountNumber;
   @Getter
   protected String testOrgRefName;
   @Getter
   protected String testTenantId;
   @Getter
   protected String defaultRealm;
   @Getter
   protected String testRealm;
   @Getter
   protected String systemRealm;
   @Getter
   protected String systemUserId;
   @Getter
   protected String testUserId;
   @Getter
   protected String defaultUserId;


   @Getter
   protected String testEmail;
   @Getter
   protected String area;
   @Getter
   protected String securityFD;
   @Getter
   protected DataDomain testDataDomain;
   @Getter
   protected DataDomain systemDataDomain;
   @Getter
   protected DataDomain defaultDataDomain;




   @PostConstruct
   public void init() {
      testAccountNumber = securityUtils.getTestAccountNumber();
      testOrgRefName = securityUtils.getTestOrgRefName();
      testTenantId = securityUtils.getTestTenantId();
      defaultRealm = securityUtils.getTestRealm();
      testRealm = securityUtils.getTestRealm();
      systemRealm = securityUtils.getSystemRealm();
      systemUserId = securityUtils.getSystemUserId();
      testEmail = securityUtils.getTestUserId();
      testUserId = securityUtils.getTestUserId();
      defaultUserId = securityUtils.getDefaultUserId();
      area = "SECURITY";
      securityFD = "ADMIN";
      testDataDomain = new DataDomain(testOrgRefName, testAccountNumber, testTenantId, 0, testUserId);
      systemDataDomain = securityUtils.getSystemDataDomain();
      defaultDataDomain = new DataDomain(securityUtils.getTestOrgRefName(),
              securityUtils.getDefaultAccountNumber(),
              securityUtils.getDefaultTenantId(), 0,
              securityUtils.getDefaultUserId());
   }




   public PrincipalContext getTestPrincipalContext (String userId, String[] roles) {
      PrincipalContext c =  new PrincipalContext.Builder()
                .withDataDomain(testDataDomain)
                .withDefaultRealm(testRealm)
                .withUserId(userId)
                .withScope("Authentication")
                .withRoles(roles).build();

      return c;
   }

   public PrincipalContext getSystemPrincipalContext (String userId, String[] roles) {
      PrincipalContext c =  new PrincipalContext.Builder()
              .withDataDomain(systemDataDomain)
              .withDefaultRealm(systemRealm)
              .withUserId(userId)
              .withScope("Authentication")
              .withRoles(roles).build();

      return c;
   }

   public PrincipalContext getDefaultPrincipalContext (String userId, String[] roles) {
      PrincipalContext c =  new PrincipalContext.Builder()
              .withDataDomain(defaultDataDomain)
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
         .withOrgRefName(testOrgRefName)
         .withAccountNumber(testAccountNumber)
         .withRealm(testRealm)
         .withOwnerId(userId)
         .withTenantId(testTenantId).build();


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
