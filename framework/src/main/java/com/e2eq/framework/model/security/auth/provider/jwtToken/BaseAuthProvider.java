package com.e2eq.framework.model.security.auth.provider.jwtToken;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.auth.UserManagementBase;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class BaseAuthProvider implements UserManagementBase {
   @Inject
   CredentialRepo credentialRepo;

   @Inject
   SecurityUtils securityUtils;


   @Override
   public void enableImpersonationWithUserId (String userId, String impersonationScript, String realmFilter, String realmToEnableIn) {
      Objects.requireNonNull(userId, "UserId cannot be null");
      Objects.requireNonNull(impersonationScript, "ImpersonationScript cannot be null");
      Objects.requireNonNull(realmFilter, "RealmFilter cannot be null");
      Objects.requireNonNull(realmToEnableIn, "RealmToEnableIn cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId, realmToEnableIn);
      if (ocred.isPresent()) {
         enableImpersonation(ocred.get().getUsername(), impersonationScript, realmFilter, realmToEnableIn, ocred.get());
      }
      else {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, realmToEnableIn));
      }
   }

   @Override
   public void enableImpersonationWithUsername (String username, String impersonationScript, String realmFilter, String realmToEnableIn) {
      Objects.requireNonNull(username, "UserId cannot be null");
      Objects.requireNonNull(impersonationScript, "ImpersonationScript cannot be null");
      Objects.requireNonNull(realmFilter, "RealmFilter cannot be null");
      Objects.requireNonNull(realmToEnableIn, "RealmToEnableIn cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUsername(username, realmToEnableIn);
      if (ocred.isPresent()) {
         enableImpersonation(ocred.get().getUsername(), impersonationScript, realmFilter, realmToEnableIn, ocred.get());
      }
      else {
         throw new NotFoundException(String.format("User with username %s not found in realm %s", username, realmToEnableIn));
      }
   }



   protected void enableImpersonation(String username, String impersonationScript, String realmFilter, String realmToEnableIn, CredentialUserIdPassword cred) {
      cred.setImpersonateFilterScript(impersonationScript);
      cred.setRealmRegEx(realmFilter);
      credentialRepo.save( realmToEnableIn, cred);
   }

   @Override
   public void disableImpersonationWithUserName (String username, String realmToDisableIn) {
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(realmToDisableIn, "RealmToDisableIn cannot be null");
      credentialRepo.findByUsername(username, realmToDisableIn).ifPresent(cred -> {
         disableImpersonation( username, realmToDisableIn, cred);
      });
   }

   @Override
   public void disableImpersonationWithUserId (String userId, String realmToDisableIn) {
      Objects.requireNonNull(userId, "UserId cannot be null");
      Objects.requireNonNull(realmToDisableIn, "RealmToDisableIn cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId, realmToDisableIn);
      if (ocred.isPresent()) {
         disableImpersonation(ocred.get().getUsername(), realmToDisableIn, ocred.get());
      }
      else {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, realmToDisableIn));
      }
   }


   protected void disableImpersonation(String username, String realmToDisableIn, CredentialUserIdPassword cred) {

      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(realmToDisableIn, "RealmToDisableIn cannot be null");

      if (cred != null && cred.getId() != null ) {
         cred.setImpersonateFilterScript(null);
         cred.setRealmRegEx(null);
         credentialRepo.save(realmToDisableIn, cred);
      } else {
         if (username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank when disabling impersonation");
         }
         if (realmToDisableIn.isBlank()) {
            throw new IllegalArgumentException("RealmToDisableIn cannot be blank when disabling impersonation");
         }

         Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUsername(username, realmToDisableIn);

         if (ocred.isPresent()) {
            cred = ocred.get();
            cred.setImpersonateFilterScript(null);
            cred.setRealmRegEx(null);
            credentialRepo.save(realmToDisableIn, cred);
         }
      }
   }

   @Override
   public void enableRealmOverrideWithUserId (String userId, String realmToOverrideIn, String regexForRealm) {
      Objects.requireNonNull(userId, "UserId cannot be null");
      Objects.requireNonNull(realmToOverrideIn, "RealmToOverrideIn cannot be null");
      Objects.requireNonNull(regexForRealm, "RegexForRealm cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId, realmToOverrideIn);
      if (ocred.isPresent()) {
         enableRealmOverrideWithUsername(ocred.get().getUsername(), realmToOverrideIn, regexForRealm);
      }
      else {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, realmToOverrideIn));
      }
   }

   @Override
   public void enableRealmOverrideWithUsername (String username, String realmToOverrideIn, String regexForRealm) {
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(realmToOverrideIn, "RealmToOverrideIn cannot be null");
      Objects.requireNonNull(regexForRealm, "RegexForRealm cannot be null");

      if (regexForRealm.isBlank()) {
         throw new IllegalArgumentException("RegexForRealm cannot be blank");
      }
      if (realmToOverrideIn.isBlank()) {
         throw new IllegalArgumentException("RealmToOverrideIn cannot be blank");
      }
      if (realmToOverrideIn.isBlank()) {
         throw new IllegalArgumentException("Realm cannot be blank");
      }
      if (username.isBlank()) {
         throw new IllegalArgumentException("Username cannot be blank");
      }

      credentialRepo.findByUsername(username, realmToOverrideIn).ifPresent(cred -> {
         cred.setRealmRegEx(regexForRealm);
         credentialRepo.save( realmToOverrideIn, cred);
      });
   }


   @Override
   public void disableRealmOverrideWithUserId (String userId, String realmToDisableIn) {
      Objects.requireNonNull(userId, "UserId cannot be null");
      Objects.requireNonNull(realmToDisableIn, "RealmToDisableIn cannot be null");
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId, realmToDisableIn);
      if (ocred.isPresent()) {
         disableRealmOverride(realmToDisableIn, ocred.get());
      }
   }

   @Override
   public void disableRealmOverrideWithUsername (String username, String realmToDisableIn) {
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(realmToDisableIn, "RealmToDisableIn cannot be null");
      credentialRepo.findByUsername(username, realmToDisableIn).ifPresent(cred -> {
         disableRealmOverride( realmToDisableIn, cred);
      });
   }

   protected void disableRealmOverride(String realmToDisableIn, CredentialUserIdPassword cred) {
      cred.setRealmRegEx(null);
      credentialRepo.save(realmToDisableIn, cred);
   }

}
