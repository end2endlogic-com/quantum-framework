package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;

import com.e2eq.framework.model.securityrules.*;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.Getter;



import java.security.SecureRandom;
import java.util.regex.Pattern;


@ApplicationScoped
@Data
public class SecurityUtils {

   @Inject
   EnvConfigUtils envConfigUtils;



   private static final SecureRandom secureRandom = new SecureRandom();


   public static  final String any = "*";
   protected final String securityArea = "security";
   protected final String name = "System Process";
   protected final String[] systemRole = {"system"};

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
      systemDataDomain = new DataDomain(envConfigUtils.getSystemOrgRefName(), envConfigUtils.getSystemAccountNumber(), envConfigUtils.getSystemTenantId(), envConfigUtils.getDefaultDataSegment(), envConfigUtils.getSystemUserId());
      testDataDomain = new DataDomain(envConfigUtils.getTestOrgRefName(), envConfigUtils.getTestAccountNumber(), envConfigUtils.getTestTenantId(), envConfigUtils.getDefaultDataSegment(), envConfigUtils.getTestUserId());
      defaultDataDomain = new DataDomain(envConfigUtils.getDefaultOrgRefName(), envConfigUtils.getDefaultAccountNumber(), envConfigUtils.getDefaultTenantId(), envConfigUtils.getDefaultDataSegment(), envConfigUtils.getDefaultUserId());
      systemSecurityResourceContext  = new ResourceContext.Builder()
              .withArea(securityArea)
              .withFunctionalDomain(any)
              .withAction(any)
              .build();

      systemPrincipalContext  = new PrincipalContext.Builder()
              .withDefaultRealm(envConfigUtils.getSystemRealm())
              .withDataDomain(systemDataDomain)
              .withUserId(envConfigUtils.getSystemUserId())
              .withRoles(systemRole)
              .withScope("systemGenerated")
              .build();

      systemSecurityBody = new SecurityURIBody.Builder()
              .withRealm(envConfigUtils.getSystemRealm())
              .withOrgRefName(envConfigUtils.getSystemOrgRefName())
              .withAccountNumber(envConfigUtils.getSystemAccountNumber())
              .withTenantId(envConfigUtils.getSystemTenantId())
              .withOwnerId(envConfigUtils.getSystemUserId())
              .build();

      systemSecurityHeader  = new SecurityURIHeader.Builder()
              .withIdentity(envConfigUtils.getSystemUserId())
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



   public DomainContext getDefaultDomainContext() {
      return new DomainContext(this.defaultDataDomain, envConfigUtils.getDefaultRealm());
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




   // Compute a list of allowed realm refNames for the given credential from a candidate set.
   // If authorizedRealms present, intersect candidates with that list; else if realmRegEx present, filter by it;
   // else, return only the default realm from the credential's domain context if included in candidates; if
   // candidates is null, return a singleton list with the default realm.
   public java.util.List<String> computeAllowedRealmRefNames(
           com.e2eq.framework.model.security.CredentialUserIdPassword credential,
           java.util.List<String> candidateRealmRefNames) {
      java.util.Objects.requireNonNull(credential, "credential cannot be null");

      // Prefer explicit list
      var realms = credential.getAuthorizedRealms();
      if (realms != null && !realms.isEmpty()) {
         java.util.Set<String> allowed = new java.util.HashSet<>();
         for (var re : realms) {
            if (re != null && re.getRealmRefName() != null) {
               allowed.add(re.getRealmRefName());
            }
         }
         if (candidateRealmRefNames == null) {
            return new java.util.ArrayList<>(allowed);
         }
         java.util.List<String> out = new java.util.ArrayList<>();
         for (String r : candidateRealmRefNames) {
            if (r != null && allowed.stream().anyMatch(a -> a.equalsIgnoreCase(r))) {
               out.add(r);
            }
         }
         return out;
      }

      // Fallback regex
      String regex = credential.getRealmRegEx();
      if (regex!= null &&!regex.isEmpty()) {
         regex = regex.replace("*", ".*");
         if (regex != null && !regex.isBlank()) {
            // Convert wildcard pattern to regex
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            java.util.regex.Pattern p;
            try {
               p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
            } catch (Exception e) {
               // invalid pattern: treat as exact string
               p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(regex), java.util.regex.Pattern.CASE_INSENSITIVE);
            }
            if (candidateRealmRefNames == null) {
               return java.util.Collections.emptyList();
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String r : candidateRealmRefNames) {
               if (r != null && p.matcher(r).matches()) {
                  out.add(r);
               }
            }
            return out;
         }
         else {
            // Empty regex: treat as no match
            return java.util.Collections.emptyList();
         }
      }

      // Neither defined: default to the credential's default realm only
      String def = credential.getDomainContext() != null ? credential.getDomainContext().getDefaultRealm() : null;
      if (def == null) {
         return java.util.Collections.emptyList();
      }
      if (candidateRealmRefNames == null) {
         return java.util.List.of(def);
      }
      for (String r : candidateRealmRefNames) {
         if (r != null && r.equalsIgnoreCase(def)) {
            return java.util.List.of(r);
         }
      }
      return java.util.Collections.emptyList();
   }

}
