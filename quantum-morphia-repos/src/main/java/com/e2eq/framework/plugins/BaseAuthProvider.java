package com.e2eq.framework.plugins;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.auth.UserManagementBase;
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
         enableImpersonation(ocred.get().getSubject(), impersonationScript, realmFilter, realmToEnableIn, ocred.get());
      }
      else {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, realmToEnableIn));
      }
   }

   @Override
   public void enableImpersonationWithSubject (String subject, String impersonationScript, String realmFilter, String realmToEnableIn) {
      Objects.requireNonNull(subject, "subject cannot be null");
      Objects.requireNonNull(impersonationScript, "ImpersonationScript cannot be null");
      Objects.requireNonNull(realmFilter, "RealmFilter cannot be null");
      Objects.requireNonNull(realmToEnableIn, "RealmToEnableIn cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findBySubject(subject, realmToEnableIn);
      if (ocred.isPresent()) {
         enableImpersonation(ocred.get().getSubject(), impersonationScript, realmFilter, realmToEnableIn, ocred.get());
      }
      else {
         throw new NotFoundException(String.format("User with username %s not found in realm %s", subject, realmToEnableIn));
      }
   }



   protected void enableImpersonation(String subject, String impersonationScript, String realmFilter, String realmToEnableIn, CredentialUserIdPassword cred) {
      cred.setImpersonateFilterScript(impersonationScript);
      cred.setRealmRegEx(realmFilter);
      credentialRepo.save( realmToEnableIn, cred);
   }

   @Override
   public void disableImpersonationWithSubject (String subject) {
      Objects.requireNonNull(subject, "subject cannot be null");

      credentialRepo.findBySubject(subject).ifPresent(cred -> {
         disableImpersonation( subject, cred);
      });
   }

   @Override
   public void disableImpersonationWithUserId (String userId) {
      Objects.requireNonNull(userId, "UserId cannot be null");


      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (ocred.isPresent()) {
         disableImpersonation(ocred.get().getSubject(),  ocred.get());
      }
      else {
         throw new NotFoundException(String.format("User with userId %s not found", userId));
      }
   }


   protected void disableImpersonation(String subject,  CredentialUserIdPassword cred) {

      Objects.requireNonNull(subject, "Subject cannot be null");


      if (cred != null && cred.getId() != null ) {
         cred.setImpersonateFilterScript(null);
         cred.setRealmRegEx(null);
         credentialRepo.save( cred);
      } else {
         if (subject.isBlank()) {
            throw new IllegalArgumentException("Subject cannot be blank when disabling impersonation");
         }


         Optional<CredentialUserIdPassword> ocred = credentialRepo.findBySubject(subject);

         if (ocred.isPresent()) {
            cred = ocred.get();
            cred.setImpersonateFilterScript(null);
            cred.setRealmRegEx(null);
            credentialRepo.save(cred);
         }
      }
   }

   @Override
   public void enableRealmOverrideWithUserId (String userId,  String regexForRealm) {
      Objects.requireNonNull(userId, "UserId cannot be null");

      Objects.requireNonNull(regexForRealm, "RegexForRealm cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (ocred.isPresent()) {
         enableRealmOverrideWithSubject(ocred.get().getSubject(), regexForRealm);
      }
      else {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId));
      }
   }

   @Override
   public void enableRealmOverrideWithSubject (String subject, String regexForRealm) {
      Objects.requireNonNull(subject, "Subject cannot be null");

      Objects.requireNonNull(regexForRealm, "RegexForRealm cannot be null");

      if (regexForRealm.isBlank()) {
         throw new IllegalArgumentException("RegexForRealm cannot be blank");
      }

      if (subject.isBlank()) {
         throw new IllegalArgumentException("Username cannot be blank");
      }

      credentialRepo.findBySubject(subject).ifPresent(cred -> {
         cred.setRealmRegEx(regexForRealm);
         credentialRepo.save( cred);
      });
   }


   @Override
   public void disableRealmOverrideWithUserId (String userId) {
      Objects.requireNonNull(userId, "UserId cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (ocred.isPresent()) {
         disableRealmOverride(ocred.get());
      }
   }

   @Override
   public void disableRealmOverrideWithSubject (String subject) {
      Objects.requireNonNull(subject, "Subject cannot be null");

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findBySubject(subject);

      if (ocred.isPresent()) {
         disableRealmOverride( ocred.get());
      } else {
         throw new NotFoundException(String.format("User with subject %s not found", subject));
      }
   }

   protected void disableRealmOverride( CredentialUserIdPassword cred) {
      cred.setRealmRegEx(null);
      credentialRepo.save( cred);
   }

}
