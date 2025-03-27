package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import org.checkerframework.checker.units.qual.C;
import org.eclipse.microprofile.config.inject.ConfigProperty;


@ApplicationScoped
@Data
public class SecurityUtils {
   // system context values
   @ConfigProperty(name = "quantum.anonymousUserId", defaultValue = "anonymous@system.com"  )
   protected String anonymousUserId;

   @ConfigProperty(name = "quantum.defaultRealm", defaultValue = "mycompanyxyz.com"  )
   protected String defaultRealm;

   @ConfigProperty(name = "quantum.defaultTenantId", defaultValue = "mycompanyxyz.com"  )
   protected String defaultTenantId;

   @ConfigProperty(name = "quantum.defaultOrg", defaultValue = "mycompanyxyz.com"  )
   @Getter
   protected String defaultOrgRefName;

   @ConfigProperty(name = "quantum.defaultAccountNumber", defaultValue = "9999999999"  )
   @Getter
   protected String defaultAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.systemOrgRefName", defaultValue = "system.com"  )
   @Getter
   protected  String systemOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.systemAccountNumber", defaultValue = "0000000000"  )
   @Getter
   protected  String systemAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com"  )
   @Getter
   protected  String systemTenantId;

   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com"  )
   @Getter
   protected  String systemRealm;

   @ConfigProperty(name = "quantum.realmConfig.systemUserId", defaultValue = "system@system.com"  )
   @Getter
   protected  String systemUserId;

   @ConfigProperty(name = "quantum.realmConfig.testUserId", defaultValue = "test@system.com"  )
   @Getter
   protected String testUserId;

   @ConfigProperty(name = "quantum.realmConfig.testOrgRefName", defaultValue = "test-system.com"  )
   @Getter
   protected String testOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.testAccountNumber", defaultValue = "0000000000"  )
   @Getter
   protected String testAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.testTenantId", defaultValue = "test-system.com"  )
   @Getter
   protected String testTenantId;

   @Getter
   @ConfigProperty(name = "quantum.realmConfig.testRealm", defaultValue = "test-system-com"  )
   protected String testRealm;

   public static  final String any = "*";
   protected final String securityArea = "security";
   protected final String name = "System Process";
   protected final String[] systemRole = {"system"};
   protected final int defaultDataSegment = 0;
   @Getter
   protected DataDomain systemDataDomain;
   @Getter
   protected SecurityURIHeader systemSecurityHeader;
   @Getter
   protected SecurityURIBody systemSecurityBody;
   @Getter
   protected PrincipalContext systemPrincipalContext;
   @Getter
   protected ResourceContext systemSecurityResourceContext;

   @PostConstruct
   public void init() {
      systemDataDomain = new DataDomain(systemOrgRefName, systemAccountNumber, systemTenantId, defaultDataSegment, systemUserId);
      systemSecurityResourceContext  = new ResourceContext.Builder()
              .withArea(securityArea)
              .withFunctionalDomain(any)
              .withAction(any)
              .build();

      systemPrincipalContext  = new PrincipalContext.Builder()
              .withDefaultRealm(systemRealm)
              .withDataDomain(systemDataDomain)
              .withUserId(systemUserId)
              .withRoles(systemRole)
              .withScope("systemGenerated")
              .build();

      systemSecurityBody = new SecurityURIBody.Builder()
              .withRealm(systemRealm)
              .withOrgRefName(systemOrgRefName)
              .withAccountNumber(systemAccountNumber)
              .withTenantId(systemTenantId)
              .withOwnerId(systemUserId)
              .build();

      systemSecurityHeader  = new SecurityURIHeader.Builder()
              .withIdentity(systemUserId)
              .withAction(any)
              .withArea(securityArea)
              .withFunctionalDomain(any).build();
   }


   //TODO: create a stack and push and pop instead of set and reset
   public void setSecurityContext() {
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
  public RuleContext getSystemRuleContext() {
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
