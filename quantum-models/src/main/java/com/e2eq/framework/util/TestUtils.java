package com.e2eq.framework.util;

import java.util.Date;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.e2eq.framework.model.persistent.base.AuditInfo;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;




@ApplicationScoped
@Data
public class TestUtils {


   @Inject
   EnvConfigUtils envConfigUtils;

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

   @ConfigProperty(name="quantum.defaultTestPassword")
   String defaultTestPassword;

   @ConfigProperty(name="quantum.defaultSystemPassword")
   String defaultSystemPassword;




   @PostConstruct
   public void init() {
      testAccountNumber = envConfigUtils.getTestAccountNumber();
      testOrgRefName = envConfigUtils.getTestOrgRefName();
      testTenantId = envConfigUtils.getTestTenantId();
      defaultRealm = envConfigUtils.getDefaultRealm();
      testRealm = envConfigUtils.getTestRealm();
      systemRealm = envConfigUtils.getSystemRealm();
      systemUserId = envConfigUtils.getSystemUserId();
      testEmail = envConfigUtils.getTestUserId();
      testUserId = envConfigUtils.getTestUserId();
      defaultUserId = envConfigUtils.getDefaultUserId();
      area = "SECURITY";
      securityFD = "ADMIN";
      testDataDomain = new DataDomain(testOrgRefName, testAccountNumber, testTenantId, 0, testUserId);
      systemDataDomain = new DataDomain(envConfigUtils.getSystemOrgRefName(), envConfigUtils.getSystemAccountNumber(), envConfigUtils.getSystemTenantId(), envConfigUtils.getDefaultDataSegment(), envConfigUtils.getSystemUserId());
      defaultDataDomain = new DataDomain(envConfigUtils.getDefaultOrgRefName(),
              envConfigUtils.getDefaultAccountNumber(),
              envConfigUtils.getDefaultTenantId(), 0,
              envConfigUtils.getDefaultUserId());
   }

   public DomainContext getTestDomainContext() {
      return DomainContext.builder()
                            .orgRefName(envConfigUtils.getTestOrgRefName())
                            .accountId(envConfigUtils.getTestAccountNumber())
                            .tenantId(envConfigUtils.getTestTenantId())
                            .defaultRealm(envConfigUtils.getTestRealm())
                            .build();
   }

   public DomainContext getSystemDomainContext() {
      return DomainContext.builder()
                            .orgRefName(envConfigUtils.getSystemOrgRefName())
                            .accountId(envConfigUtils.getSystemAccountNumber())
                            .tenantId(envConfigUtils.getSystemTenantId())
                            .defaultRealm(envConfigUtils.getSystemRealm())
                            .build();

   }

   public DomainContext getDefaultDomainContext() {
      return DomainContext.builder()
                            .orgRefName(envConfigUtils.getDefaultOrgRefName())
                            .accountId(envConfigUtils.getDefaultAccountNumber())
                            .tenantId(envConfigUtils.getDefaultTenantId())
                            .defaultRealm(envConfigUtils.getDefaultRealm())
                            .build();

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




   public static void clearSecurityContext() {
      SecurityContext.setPrincipalContext(null);
      SecurityContext.setResourceContext(null);
   }
}
