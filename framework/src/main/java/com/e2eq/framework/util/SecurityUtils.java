package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Data;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.SecureRandom;
import java.util.Random;
import java.util.Deque;
import java.util.LinkedList;


@ApplicationScoped
@Data
public class SecurityUtils {
   // system context values
   @ConfigProperty(name = "quantum.anonymousUserId", defaultValue = "anonymous@system.com"  )
   protected String anonymousUserId;

   @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "mycompanyxyz-com"  )
   protected String defaultRealm;

   @ConfigProperty(name = "quantum.realmConfig.defaultTenantId", defaultValue = "mycompanyxyz.com"  )
   protected String defaultTenantId;

   @ConfigProperty(name = "quantum.realmConfig.defaultOrgRefName", defaultValue = "mycompanyxyz.com"  )
   @Getter
   protected String defaultOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.defaultAccountNumber", defaultValue = "9999999999"  )
   @Getter
   protected String defaultAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.systemOrgRefName", defaultValue = "system.com"  )
   @Getter
   protected  String systemOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.systemAccountNumber", defaultValue = "0000000000"  )
   @Getter
   protected  String systemAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.systemTenantId", defaultValue = "system.com"  )
   @Getter
   protected  String systemTenantId;

   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com"  )
   @Getter
   protected  String systemRealm;

   @ConfigProperty(name = "quantum.realmConfig.systemUserId", defaultValue = "system@system.com"  )
   @Getter
   protected  String systemUserId;

   @ConfigProperty(name = "quantum.realmConfig.testUserId", defaultValue = "test@test-system.com"  )
   @Getter
   protected String testUserId;

   @ConfigProperty(name = "quantum.realmConfig.defaultUserId", defaultValue = "test@mycompanyxyz.com"  )
   @Getter
   protected String defaultUserId;

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

   private static final SecureRandom secureRandom = new SecureRandom();


   public static  final String any = "*";
   protected final String securityArea = "security";
   protected final String name = "System Process";
   protected final String[] systemRole = {"system"};
   protected final int defaultDataSegment = 0;
   @Getter
   protected DataDomain systemDataDomain;
   @Getter
   protected DataDomain testDataDomain;
   @Getter
   protected DataDomain defaultDataDomain;
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
      testDataDomain = new DataDomain(testOrgRefName, testAccountNumber, testTenantId, defaultDataSegment, testUserId);
      defaultDataDomain = new DataDomain(defaultOrgRefName, defaultAccountNumber, defaultTenantId, defaultDataSegment, defaultUserId);
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


   private static final ThreadLocal<Deque<PrincipalContext>> principalContextStack =
         ThreadLocal.withInitial(java.util.LinkedList::new);
   private static final ThreadLocal<Deque<ResourceContext>> resourceContextStack =
         ThreadLocal.withInitial(java.util.LinkedList::new);

   // push the current contexts and set the system context
   public void setSecurityContext() {
      principalContextStack.get().push(SecurityContext.getPrincipalContext().orElse(null));
      resourceContextStack.get().push(SecurityContext.getResourceContext().orElse(null));
      SecurityContext.setPrincipalContext(systemPrincipalContext);
      SecurityContext.setResourceContext(systemSecurityResourceContext);
   }

   // restore the previous contexts
   public void popSecurityContext() {
      PrincipalContext pctx = principalContextStack.get().poll();
      ResourceContext rctx = resourceContextStack.get().poll();
      if (pctx == null) {
         SecurityContext.clearPrincipalContext();
      } else {
         SecurityContext.setPrincipalContext(pctx);
      }
      if (rctx == null) {
         SecurityContext.clearResourceContext();
      } else {
         SecurityContext.setResourceContext(rctx);
      }
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

   public DomainContext getDefaultDomainContext() {
      return new DomainContext(this.defaultDataDomain, this.defaultRealm);
   }



   public String randomPassword(int length) {
      if (length < 4) {
         throw new IllegalArgumentException("Password length must be at least 4 to include required character types.");
      }

      String upperCaseLetters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
      String numbers = "0123456789";
      String specialCharacters = "!#$%^*?";
      String lowerCaseLetters = "abcdefghijklmnopqrstuvwxyz";
      String allCharacters = upperCaseLetters + lowerCaseLetters + numbers + specialCharacters;

      StringBuilder sb = new StringBuilder(length);

      // Ensure at least one character from each required set
      sb.append(upperCaseLetters.charAt(secureRandom.nextInt(upperCaseLetters.length())));
      sb.append(numbers.charAt(secureRandom.nextInt(numbers.length())));
      sb.append(lowerCaseLetters.charAt(secureRandom.nextInt(upperCaseLetters.length())));
      sb.append(specialCharacters.charAt(secureRandom.nextInt(specialCharacters.length())));

      // Fill the rest of the password length with random characters
      for (int i = 4; i < length; i++) {
         sb.append(allCharacters.charAt(secureRandom.nextInt(allCharacters.length())));
      }

      // Shuffle the characters to ensure randomness
      return shuffleString(sb.toString());
   }

   private String shuffleString(String input) {
      char[] characters = input.toCharArray();
      for (int i = 0; i < characters.length; i++) {
         int randomIndex = secureRandom.nextInt(characters.length);
         char temp = characters[i];
         characters[i] = characters[randomIndex];
         characters[randomIndex] = temp;
      }
      return new String(characters);
   }







}
