package com.e2eq.framework.model.auth.provider.jwtToken;


import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;


import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.auth.ApplicationAuthorizationResolver;
import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.ClaimsAuthProvider;
import com.e2eq.framework.model.auth.ProviderClaims;
import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialRefreshToken;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.plugins.BaseAuthProvider;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.Arrays;

@ApplicationScoped
public class CustomTokenAuthProvider extends BaseAuthProvider implements AuthProvider, ClaimsAuthProvider, UserManagement{

   @ConfigProperty(name = "mp.jwt.verify.issuer")
   String issuer;

   @ConfigProperty(name = "com.b2bi.jwt.duration")
   Long durationInSeconds;

   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com")
   String systemRealm;

   @ConfigProperty(name = "quarkus.mongodb.connection-string")
   String mongodbConnectionString;

   @Inject
   JWTParser jwtParser;

   @Inject
   EnvConfigUtils envConfigUtils;

   @Inject
   CredentialRepo credentialRepo;

   @Inject
   RealmRepo realmRepo;

   @Inject
   UserRealmRoleRepo userRealmRoleRepo;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   UserGroupRepo userGroupRepo;

   // Note: Union of userGroup roles is handled centrally by the framework during permission checks.



   // problems with permissions if you need to save
   //@Inject
   //CredentialRefreshTokenRepo credentialRefreshTokenRepo;

   public String getName () {
      return "custom";
   }

   @Override
   public String getIssuer() {
      return issuer;
   }

   /**
    * Generate a long-lived service token for MCP servers, service accounts, etc.
    * @param subject the token subject (user/service identity)
    * @param roles   the roles to embed in the token
    * @param expirationSeconds seconds until expiry, or null for non-expiring (100 years)
    * @return signed JWT token string
    */
   public String generateServiceToken(String subject, Set<String> roles, Long expirationSeconds) {
      return generateServiceToken(subject, roles, expirationSeconds, null);
   }

   /**
    * Realm-scoped variant: stamps the signed {@code realm} claim so data planes running
    * delegated-claims validation (which fail closed on a missing realm claim) accept the
    * token. No tenant data-domain claims are stamped — the consuming plane resolves the
    * data domain from the realm (system realm requires the {@code system} role).
    */
   public String generateServiceToken(String subject, Set<String> roles, Long expirationSeconds, String realm) {
      return generateServiceToken(subject, roles, expirationSeconds, realm, null);
   }

   /**
    * Compatibility overload for older callers that still send realm/audience
    * parameters. Service identity is deliberately not resource-bound, so both
    * parameters are ignored.
    */
   public String generateServiceToken(String subject, Set<String> roles, Long expirationSeconds,
                                      String realm, Set<String> audiences) {
      return generateServiceToken(subject, roles, expirationSeconds, realm, audiences, null);
   }

   /**
    * Application-scoped service passport. The active application comes from
    * the authenticated minting session and is signed as {@code azp}; realm and
    * audiences remain deliberately excluded from service identity.
    */
   public String generateServiceToken(String subject, Set<String> roles, Long expirationSeconds,
                                      String realm, Set<String> audiences, String activeApplication) {
      long duration = (expirationSeconds != null) ? expirationSeconds : 60L * 60 * 24 * 365 * 100;
      try {
         return TokenUtils.generateServiceToken(
                 subject, roles, activeApplication, TokenUtils.expiresAt(duration), issuer);
      } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
         throw new RuntimeException("Failed to generate service token", e);
      }
   }




   @Override
   public void changePassword(String userId, String oldPassword, String newPassword, Boolean forceChangePassword) {
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (!ocred.isPresent()) {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, envConfigUtils.getSystemRealm()));
      }

      CredentialUserIdPassword cred = ocred.get();
      if (!EncryptionUtils.hashPassword(oldPassword).equals(cred.getPasswordHash())) {
         throw new SecurityException("Old Password was incorrect");
      }
      // Normalize hashing algorithm to the canonical value and update hash
      cred.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
      cred.setPasswordHash(EncryptionUtils.hashPassword(newPassword));
      cred.setForceChangePassword(forceChangePassword);
      cred.setAuthProviderName(getName());
      credentialRepo.save( cred);
   }

   @Override
   public void resendTemporaryPassword(String userId) throws SecurityException {
      throw new UnsupportedOperationException(
         "Resending temporary password is not supported by CustomTokenAuthProvider");
   }

   @Override
   public void changeEmail(String userId, String newEmail) throws SecurityException {
      throw new UnsupportedOperationException(
         "Changing email is not supported by CustomTokenAuthProvider");
   }

   @Override
   public void resetPassword(String userId, String newPassword, Boolean forceChangePassword) {
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (!ocred.isPresent()) {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, envConfigUtils.getSystemRealm()));
      }

      CredentialUserIdPassword cred = ocred.get();
      cred.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
      cred.setPasswordHash(EncryptionUtils.hashPassword(newPassword));
      cred.setForceChangePassword(forceChangePassword);
      cred.setAuthProviderName(getName());
      credentialRepo.save(cred);
   }

   @Override
   public String createUser (String userId, String password,  Set<String> roles,
                           DomainContext domainContext) throws SecurityException {
      return createUser( userId, password, false, roles, domainContext);
   }





   // New overloads supporting explicit DataDomain
   @Override
   public String createUser(String userId, String password,
                          Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException {
      return createUser( userId, password, null, roles, domainContext, dataDomain);
   }

   @Override
   public String createUser (String userId, String password, Boolean forceChangePassword
      , Set<String> roles, DomainContext domainContext) throws SecurityException {
      return createUser(userId, password, forceChangePassword, roles, domainContext, (String) null);
   }

   @Override
   public String createUser (String userId, String password, Boolean forceChangePassword
      , Set<String> roles, DomainContext domainContext, String applicationRegEx) throws SecurityException {

      Objects.requireNonNull(userId, "UserId cannot be null");
      Objects.requireNonNull(password, "Password cannot be null");
      Objects.requireNonNull(domainContext, "DomainContext cannot be null");

      String subject;
      try {
         byte[] utf8Bytes = password.getBytes("UTF-8");
         int byteLength = utf8Bytes.length;

         if (byteLength > 72) { // Limit subject to 17 characters due to Bcrypt limitations
            throw new SecurityException("subject too long must be smaller than 17 characters");
         }
      } catch (UnsupportedEncodingException ex) {
         throw new SecurityException("Security exception: " + ex.getMessage());
      }
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (ocred.isPresent()) {
         if (!ocred.get().getDomainContext().equals(domainContext)) {
            throw new SecurityException(String.format("User with the same userId:%s already exists with different " +
                                                         "domainContext:%s, than given domainContext:%s", userId,
               ocred.get().getDomainContext(), domainContext));
         }
         if (!Arrays.equals(ocred.get().getRoles(), roles.toArray(new String[roles.size()]))) {
            throw new SecurityException(String.format("User with the same userId:%s already exists with different " +
                                                         "roles:%s, than given roles:%s", userId,
               ocred.get().getRoles(), roles));
         }
         throw new SecurityException(String.format("User with userId:%s  already exists with same " +
                                                      "domain context, and roles", userId));
      } else {

         CredentialUserIdPassword credential = new CredentialUserIdPassword();
         credential.setUserId(userId);
         subject = UUID.randomUUID().toString();
         credential.setRefName(subject);
         credential.setSubject(subject);
         credential.setForceChangePassword(forceChangePassword);
         // Enforce canonical hashing algorithm
         credential.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
         credential.setPasswordHash(EncryptionUtils.hashPassword(password));
         credential.setDomainContext(domainContext);
         credential.setRoles(roles.toArray(new String[roles.size()]));
         credential.setLastUpdate(new Date());
         credential.setAuthProviderName(getName());
         // createUser is the human-account path; service/system principals are
         // created through their own dedicated surfaces which stamp their type.
         credential.setAccountType(com.e2eq.framework.model.security.AccountType.USER);
         credential.setApplicationRegEx(resolveApplicationBoundary(applicationRegEx));
         credentialRepo.save(credential);
      }
      return subject;

   }

   // Main implementation that accepts explicit DataDomain
   @Override
   public String createUser(String userId, String password, Boolean forceChangePassword,
                          Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException {
      Objects.requireNonNull(userId, "UserId cannot be null");

      Objects.requireNonNull(password, "Password cannot be null");
      Objects.requireNonNull(domainContext, "DomainContext cannot be null");
      String subject;
      try {
         byte[] utf8Bytes = password.getBytes("UTF-8");
         int byteLength = utf8Bytes.length;
         if (byteLength > 72) {
            throw new SecurityException("subject too long must be smaller than 17 characters");
         }
      } catch (UnsupportedEncodingException ex) {
         throw new SecurityException("Security exception: " + ex.getMessage());
      }

      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (ocred.isPresent()) {

         if (!ocred.get().getDomainContext().equals(domainContext)) {
            throw new SecurityException(String.format("User with the same userId:%s already exists with different domainContext:%s, than given domainContext:%s", userId, ocred.get().getDomainContext(), domainContext));
         }
         if (!Arrays.equals(ocred.get().getRoles(), roles.toArray(new String[roles.size()]))) {
            throw new SecurityException(String.format("User with the same userId:%s already exists with different roles:%s, than given roles:%s", userId, ocred.get().getRoles(), roles));
         }
         throw new SecurityException(String.format("User with userId:%s and already exists with same domain context, and roles", userId));
      } else {
         CredentialUserIdPassword credential = new CredentialUserIdPassword();
         credential.setUserId(userId);
         subject = UUID.randomUUID().toString();
         credential.setSubject(subject);
         credential.setForceChangePassword(forceChangePassword);
         // Enforce canonical hashing algorithm
         credential.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
         credential.setPasswordHash(EncryptionUtils.hashPassword(password));
         credential.setDomainContext(domainContext);
         credential.setRoles(roles.toArray(new String[roles.size()]));
         credential.setLastUpdate(new Date());

         // Set explicit DataDomain if provided; else leave null to let ValidationInterceptor set from SecurityContext
         if (dataDomain != null) {
            if (dataDomain.getOwnerId() == null || dataDomain.getOwnerId().isEmpty()) {
               dataDomain.setOwnerId(userId);
            }
            credential.setDataDomain(dataDomain);
         }
         credential.setAuthProviderName(getName());
         credential.setApplicationRegEx(resolveApplicationBoundary(null));
         credentialRepo.save(credential);

      }
      return subject;
   }

   /**
    * Application boundary stamped on every credential this provider creates. Login
    * mints application-scoped tokens (the {@code aud} set comes from the credential's
    * boundary or a per-realm application grant) and fails fast with a typed 422 when a
    * credential has neither — so a credential created without a boundary would be
    * unloginable. An explicit boundary is validated and used; otherwise the documented
    * default {@code "*"} applies (any application — the pre-application-scoping
    * behavior), which admins can narrow later with a concrete pattern or per-realm
    * grants. Invalid patterns are rejected here, at create time, because the resolver
    * fails closed on them at login — accepting one would recreate the unloginable-user
    * defect with a harder-to-diagnose cause.
    */
   private static String resolveApplicationBoundary(String applicationRegEx) {
      String boundary = applicationRegEx == null ? null : applicationRegEx.trim();
      if (boundary == null || boundary.isEmpty()) {
         return ApplicationAuthorizationResolver.WILDCARD;
      }
      if (!ApplicationAuthorizationResolver.WILDCARD.equals(boundary)) {
         try {
            java.util.regex.Pattern.compile(boundary);
         } catch (java.util.regex.PatternSyntaxException e) {
            throw new jakarta.ws.rs.WebApplicationException(
               "Invalid applicationRegEx '" + boundary + "': " + e.getDescription()
                  + ". Use '*' for any application or a valid regular expression.",
               422);
         }
      }
      return boundary;
   }


   @Override
   public Optional<String> getSubjectForUserId (String userId) throws SecurityException {
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId);
      if (ocred.isPresent()){
         return Optional.of(ocred.get().getSubject());
      } else {
         return Optional.empty();
      }
   }


   @Override
   public Optional<String> getUserIdForSubject ( String subject) throws SecurityException {
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findBySubject(subject);
      if (ocred.isPresent()){
         return Optional.of(ocred.get().getUserId());
      } else {
         return Optional.empty();
      }
   }





   @Override
   public boolean removeUserWithUserId (String userId) throws ReferentialIntegrityViolationException {

      Objects.requireNonNull(userId, "UserId cannot be null");
      Optional<CredentialUserIdPassword> ocredentialUserIdPassword = credentialRepo.findByUserId(userId,envConfigUtils.getSystemRealm(),true);
      if (ocredentialUserIdPassword.isPresent()) {
         long val = credentialRepo.delete(envConfigUtils.getSystemRealm(), ocredentialUserIdPassword.get());
         if (val != 0)
            return true;
         else
            return false;
      }
      return false;
   }





   @Override
   public boolean removeUserWithSubject (String subject) throws ReferentialIntegrityViolationException {
      Objects.requireNonNull(subject, "Subject cannot be null");
      Optional<CredentialUserIdPassword> ocredentialUserIdPassword = credentialRepo.findBySubject(subject);
      if (ocredentialUserIdPassword.isPresent()) {
         long val = credentialRepo.delete(envConfigUtils.getSystemRealm(), ocredentialUserIdPassword.get());
         if (val != 0)
            return true;
         else
            return false;
      }
      return false;
   }

   @Override
   public void assignRolesForUserId( String userId, Set<String> roles) throws SecurityException {

      Objects.requireNonNull(userId, "userId cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");

      credentialRepo.findByUserId(userId).ifPresentOrElse(credential -> {
         Set<String> existingRoles = new HashSet<>(Arrays.asList(credential.getRoles()));
         existingRoles.addAll(roles);
         credential.setRoles(existingRoles.toArray(new String[existingRoles.size()]));
         credentialRepo.save(credential);
      }, () -> {
         throw new SecurityException(String.format("User not found userId: %s", userId));
      });
   }



   @Override
   public void assignRolesForSubject ( String subject, Set<String> roles) throws SecurityException {

      Objects.requireNonNull(subject, "userId cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");

      credentialRepo.findBySubject(subject).ifPresentOrElse(credential -> {
         Set<String> existingRoles = new HashSet<>(Arrays.asList(credential.getRoles()));
         existingRoles.addAll(roles);
         credential.setRoles(existingRoles.toArray(new String[existingRoles.size()]));
         credentialRepo.save(credential);
      }, () -> {
         throw new SecurityException(String.format("User not found subject: %s", subject));
      });
   }



   public void removeRolesForUserId (String userId, Set<String> roles) throws SecurityException {

      Objects.requireNonNull(userId, "userId cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");
      if (roles.isEmpty()) {
         throw new IllegalArgumentException("Roles cannot be empty");
      }
      credentialRepo.findByUserId(userId).ifPresentOrElse(
         credential -> {
            Set<String> existingRoles = new HashSet<>(Arrays.asList(credential.getRoles()));
            existingRoles.removeAll(roles);
            credential.setRoles(existingRoles.toArray(new String[existingRoles.size()]));
            credentialRepo.save(credential);
         }, () -> {
            throw new SecurityException("User not found with userId: " + userId);
         });

   }


   public void removeRolesForSubject (String subject, Set<String> roles) throws SecurityException {

      Objects.requireNonNull(subject, "subject cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");
      if (roles.isEmpty()) {
         throw new IllegalArgumentException("Roles cannot be empty");
      }
      credentialRepo.findBySubject(subject).ifPresentOrElse(
         credential -> {
            Set<String> existingRoles = new HashSet<>(Arrays.asList(credential.getRoles()));
            existingRoles.removeAll(roles);
            credential.setRoles(existingRoles.toArray(new String[existingRoles.size()]));
            credentialRepo.save(credential);
         }, () -> {
            throw new SecurityException("User not found with userId: " + subject);
         });
   }



   @Override
   public Set<String> getUserRolesForSubject ( String subject) throws SecurityException {
      Set<String> rolesHolder = new HashSet<>();

      credentialRepo.findBySubject(subject).ifPresentOrElse(
         credential -> {
            rolesHolder.addAll(Arrays.asList(credential.getRoles()));
         },
         () -> {
            throw new SecurityException(String.format("User with subject:%s not found: ", subject));
         }
      );

      return rolesHolder;
   }




   @Override
   public Set<String> getUserRolesForUserId ( String userId) throws SecurityException {
      Set<String> rolesHolder = new HashSet<>();

      credentialRepo.findByUserId(userId).ifPresentOrElse(
         credential -> {
            rolesHolder.addAll(Arrays.asList(credential.getRoles()));
         },
         () -> {
            throw new SecurityException(String.format("User with userId:%s not found: ", userId));
         }
      );

      return rolesHolder;
   }




   @Override
   public boolean subjectExists (String subject) throws SecurityException {
      return credentialRepo.findBySubject(subject).isPresent();
   }




   @Override
   public boolean userIdExists ( String userId) throws SecurityException {
      return credentialRepo.findByUserId(userId).isPresent();
   }

   @Override
   public LoginResponse login (String userId, String password) {
      return login(userId, password, null, null);
   }

   @Override
   public LoginResponse login (String userId, String password, String applicationId) {
      return login(userId, password, applicationId, null);
   }

   @Override
   public LoginResponse login (String userId, String password, String applicationId, String requestedRealm) {
      try {
         String configuredRealm = envConfigUtils.getSystemRealm();
         Log.infof("CustomProvider: Checking for auth against %s realm", configuredRealm);
         // Use ignoreRules=true so credential lookup succeeds for unauthenticated callers (login form)
         Optional<CredentialUserIdPassword> ocredential = credentialRepo.findByUserId(userId, configuredRealm, true);


         if (ocredential.isPresent()) {
            CredentialUserIdPassword credential = ocredential.get();
            // Disabled credentials must not authenticate. null activeStatus is
            // legacy-allowed (rows that predate the flag); INACTIVE/DELETED are
            // authoritative. The negative envelope stays generic so account
            // state is not disclosed to callers; the real reason is logged.
            if (credentialDisabled(credential)) {
               Log.warnf("Login rejected: credential %s has activeStatus=%s", userId, credential.getActiveStatus());
               return new LoginResponse(false,
                  new LoginNegativeResponse(userId,
                     400,
                     401,
                     "Invalid credentials",
                     "Invalid credentials",
                     "credentials did not match",
                     envConfigUtils.getSystemRealm())
               );
            }
            // Account-nature invariant: a USER account must have a directory
            // profile (half-created accounts can otherwise log in while being
            // invisible to every admin surface). Legacy credentials (null
            // accountType) are flagged in the log but not locked out.
            if (credential.getAccountType() == com.e2eq.framework.model.security.AccountType.USER
                  || credential.getAccountType() == null) {
               boolean hasProfile = userProfileRepo
                     .getBySubject(envConfigUtils.getSystemRealm(), credential.getSubject()).isPresent()
                     || userProfileRepo
                     .getByUserIdWithIgnoreRules(envConfigUtils.getSystemRealm(), credential.getUserId()).isPresent();
               if (!hasProfile) {
                  if (credential.getAccountType() == com.e2eq.framework.model.security.AccountType.USER) {
                     Log.warnf("Login rejected: USER account %s has no directory profile (half-created account)",
                           userId);
                     return new LoginResponse(false,
                        new LoginNegativeResponse(userId,
                           400,
                           401,
                           "Invalid credentials",
                           "Invalid credentials",
                           "credentials did not match",
                           envConfigUtils.getSystemRealm())
                     );
                  }
                  Log.warnf("Unclassified credential %s has no directory profile — classify its accountType "
                        + "and create a profile, or mark it SERVICE/SYSTEM", userId);
               }
            }
            if (credential.getForceChangePassword() != null && credential.getForceChangePassword()) {
               return new LoginResponse(false,
                  new LoginNegativeResponse(userId,
                     400,
                     403,
                     "Password change required",
                     "PasswordChangeRequired",
                     "Password change required",
                     envConfigUtils.getSystemRealm())
               );
            }
            String alg = credential.getHashingAlgorithm();
            if (alg != null && (alg.equalsIgnoreCase("BCrypt.default") || alg.toLowerCase().startsWith("bcrypt") || alg.toLowerCase().equals(EncryptionUtils.hashAlgorithm().toLowerCase()))) {
               boolean isCredentialValid = EncryptionUtils.checkPassword(password, credential.getPasswordHash());
               if (isCredentialValid) {
                  // String authToken = generateAuthToken(userId);
                  String credentialRealm = (credential.getDomainContext() != null)
                     ? credential.getDomainContext().getDefaultRealm()
                     : envConfigUtils.getSystemRealm();
                   String tokenRealm = (requestedRealm == null || requestedRealm.isBlank())
                      ? credentialRealm
                      : requestedRealm.trim();
                   Optional<Realm> operatingRealm = Objects.equals(credentialRealm, tokenRealm)
                      ? Optional.empty()
                      : realmRepo.findByRefName(tokenRealm, true, envConfigUtils.getSystemRealm());
                   if (!Objects.equals(credentialRealm, tokenRealm) && operatingRealm.isEmpty()) {
                      return new LoginResponse(false,
                         new LoginNegativeResponse(userId,
                            403,
                            403,
                            "Realm is not registered: " + tokenRealm,
                            "RealmNotAuthorized",
                            tokenRealm,
                            tokenRealm));
                   }
                  boolean credentialRealmAuthorized = credentialAuthorizesRealm(
                     securityUtils, credential, tokenRealm);
                  Set<String> groups = new HashSet<>();
                  if (Objects.equals(credentialRealm, tokenRealm) && credential.getRoles() != null) {
                     groups.addAll(Arrays.asList(credential.getRoles()));
                  }

                  String subject = credential.getSubject();
                  if (subject == null || subject.isEmpty()) {
                     throw new IllegalStateException(
                        "Credential subject is required for userId: " + userId);
                  }

                  // B4 membership ADR: one global identity, per-realm roles.
                  // Union the user's UserRealmRole assignment for the realm
                  // this token is scoped to into the token's role claims.
                  Optional<UserRealmRole> realmAssignment = Optional.empty();
                  java.util.Set<String> realmAssignedRoles = new java.util.LinkedHashSet<>();
                  try {
                     realmAssignment = userRealmRoleRepo.findActiveAssignmentForRealmWithIgnoreRules(
                        userId, tokenRealm, envConfigUtils.getSystemRealm());
                     realmAssignment.map(UserRealmRole::getRoles).ifPresent(realmAssignedRoles::addAll);
                  } catch (Exception e) {
                     return new LoginResponse(false,
                        new LoginNegativeResponse(userId,
                           403,
                           403,
                           "Unable to verify authorization for realm: " + tokenRealm,
                           "RealmAuthorizationUnavailable",
                           tokenRealm,
                           tokenRealm));
                  }
                  // A role assignment describes what the identity may do inside
                  // a realm; it must never expand the credential's realm
                  // boundary. Preserve the 1.3 fail-closed contract: the
                  // credential must authorize the realm independently.
                  if (!credentialRealmAuthorized) {
                     return new LoginResponse(false,
                        new LoginNegativeResponse(userId,
                           403,
                           403,
                           "Not authorized for realm: " + tokenRealm,
                           "RealmNotAuthorized",
                           tokenRealm,
                           tokenRealm));
                  }
                  groups.addAll(realmAssignedRoles);

                  // Application-scoped auth: resolve which application(s) this token is
                  // scoped to from the user's per-realm grant. LEGACY = no grant configured
                  // (single default audience, unchanged behavior); RESOLVED = multi-aud
                  // token + azp; AMBIGUOUS/DENIED short-circuit to a negative response the
                  // login resource maps to 400 (needs selection) / 403 (not authorized).
                  ApplicationAuthorizationResolver.Result appAuth = ApplicationAuthorizationResolver.resolve(
                      realmAssignment.map(UserRealmRole::getAuthorizedApplications).orElse(null),
                      credential.getApplicationRegEx(),
                      realmAssignment.map(UserRealmRole::getDefaultApplication).orElse(null),
                      applicationId);
                  switch (appAuth.outcome()) {
                     case AMBIGUOUS:
                        java.util.List<String> selectionCandidates = appAuth.candidates();
                        if (selectionCandidates.isEmpty()) {
                           // Pattern grants can't enumerate themselves; the realm
                           // catalog doubles as the application-usage registry, so
                           // offer the distinct in-use applications that satisfy
                           // the credential's pattern — a selection prompt with an
                           // empty candidate list is unactionable.
                           String pattern = credential.getApplicationRegEx();
                           selectionCandidates = realmRepo.findDistinctApplicationRefNames().stream()
                              .filter(app -> ApplicationAuthorizationResolver.matches(pattern, app))
                              .toList();
                        }
                        return new LoginResponse(false,
                           new LoginNegativeResponse(userId, 400, 300,
                              "Multiple applications are authorized; specify which application to sign into",
                              "ApplicationSelectionRequired",
                              String.join(",", selectionCandidates),
                              tokenRealm));
                     case DENIED:
                        return new LoginResponse(false,
                           new LoginNegativeResponse(userId, 403, 403,
                              "Not authorized for application: " + appAuth.deniedApplication(),
                              "ApplicationNotAuthorized",
                              appAuth.deniedApplication(),
                              tokenRealm));
                     case LEGACY:
                        // The credential authenticated but carries no application
                        // authorization at all (applicationRegEx unset AND no per-realm
                        // application grant). Application-scoped minting cannot produce a
                        // valid audience set for it, so fail fast with a diagnostic the
                        // operator can act on instead of letting the mint blow up as a 500.
                        return new LoginResponse(false,
                           new LoginNegativeResponse(userId, 422, 422,
                              "Credential for user '" + userId + "' has no application authorization in realm '"
                                 + tokenRealm + "': applicationRegEx is unset and the realm assigns no application "
                                 + "grant. Set an application boundary on the credential (e.g. applicationRegEx '*') "
                                 + "or grant applications on the user's realm role, then retry.",
                              "ApplicationAuthorizationMissing",
                              "applicationRegEx",
                              tokenRealm));
                     default:
                        break; // RESOLVED — mint below
                  }

                  DomainContext tokenDomainContext = Objects.equals(credentialRealm, tokenRealm)
                     ? credential.getDomainContext()
                     : operatingRealm.map(Realm::getDomainContext).orElse(null);
                  if (tokenDomainContext == null) {
                     throw new IllegalStateException(
                        "Realm has no data-domain configuration: " + tokenRealm);
                  }

                  // Only RESOLVED reaches the mint: LEGACY/AMBIGUOUS/DENIED all returned
                  // typed negative responses above, so the audience set is always non-empty.
                  if (appAuth.wildcard()) {
                     Log.warnf("Wildcard application grant exercised (audit): user=%s realm=%s activeApplication=%s",
                        userId, tokenRealm, appAuth.activeApplication());
                  }
                  java.util.Set<String> userGroupRoles = resolveUserGroupRoles(
                     subject, tokenRealm, appAuth.activeApplication());
                  groups.addAll(userGroupRoles);
                  String authToken = TokenUtils.generateUserToken(
                     subject,
                     credential.getUserId(),
                     groups,
                     tokenRealm,
                     tokenDomainContext.getTenantId(),
                     tokenDomainContext.getOrgRefName(),
                     tokenDomainContext.getAccountId(),
                     appAuth.audiences(),
                     appAuth.activeApplication(),
                     // Signed realm boundary: lets delegated-claims validators
                     // authorize X-Realm switches within the credential's own
                     // declared boundary instead of pinning to the login realm.
                     credential.getRealmRegEx(),
                     TokenUtils.expiresAt(durationInSeconds),
                     issuer);

                  String refreshToken = generateRefreshToken(
                     subject,
                     credential.getUserId(),
                     tokenRealm,
                     appAuth.resolved() ? appAuth.activeApplication() : null,
                     authToken,
                     durationInSeconds);
                  SecurityIdentity identity = validateAccessToken(authToken);
                  // Compute role provenance locally for login response: IDP + CREDENTIAL + USERGROUP
                  // Auth plugins do NOT need to do this; it's optional for login response only.
                  // Use the credential's realm context instead of hardcoded system realm
                  String realm = tokenRealm;
                  java.util.Set<String> idpRoles = (identity != null) ? new java.util.LinkedHashSet<>(identity.getRoles()) : java.util.Set.of();
                  java.util.Set<String> credentialRoles = credentialRolesForRealm(
                     credential.getRoles(), credentialRealm, tokenRealm);
                  java.util.Set<String> rolesSet = new java.util.LinkedHashSet<>();
                  rolesSet.addAll(idpRoles);
                  rolesSet.addAll(credentialRoles);
                  rolesSet.addAll(userGroupRoles);
                  rolesSet.addAll(realmAssignedRoles);

                  java.util.List<RoleAssignment> roleAssignments = rolesSet.stream()
                          .filter(Objects::nonNull)
                          .map(role -> {
                             java.util.EnumSet<RoleSource> src = java.util.EnumSet.noneOf(RoleSource.class);
                             if (idpRoles.contains(role)) src.add(RoleSource.TOKEN);
                             if (credentialRoles.contains(role)) src.add(RoleSource.CREDENTIAL);
                             if (userGroupRoles.contains(role)) src.add(RoleSource.USERGROUP);
                             if (realmAssignedRoles.contains(role)) src.add(RoleSource.REALM);
                             return new RoleAssignment(role, src);
                          })
                          .toList();

                  return new LoginResponse(
                     true,
                     new LoginPositiveResponse(
                        userId,
                        identity,
                        rolesSet,
                        roleAssignments,
                        authToken,
                        refreshToken,
                        TokenUtils.currentTimeInSecs() + durationInSeconds,
                        mongodbConnectionString,
                        realm,
                        appAuth.resolved() ? appAuth.audiences() : null,
                        appAuth.resolved() ? appAuth.activeApplication() : null)
                  );
               } else {
                  return new LoginResponse(false,
                     new LoginNegativeResponse(userId,
                        400,
                        401,
                        "Invalid credentials",
                        "Invalid credentials",
                        "credentials did not match",
                         envConfigUtils.getSystemRealm())
                  );
               }
            } else {
               throw new UnsupportedOperationException("Unsupported hashing algorithm: " + credential.getHashingAlgorithm());
            }
         } else {
            return new LoginResponse(false,
               new LoginNegativeResponse(userId,
                  400,
                  404,
                  "Invalid credentials",
                  "NotFound",
                  "Credentials not found",
                   envConfigUtils.getSystemRealm())
            );
         }
      } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
         return new LoginResponse(false,
            new LoginNegativeResponse(userId,
               400,
               500,
               e.getMessage(),
               e.getClass().getName(),
               e.toString(),
               envConfigUtils.getSystemRealm()
            )
         );
      }
   }

   static Set<String> credentialRolesForRealm(String[] roles, String credentialRealm, String tokenRealm) {
      if (!Objects.equals(credentialRealm, tokenRealm) || roles == null) {
         return Set.of();
      }
      return new LinkedHashSet<>(Arrays.asList(roles));
   }

   @Override
   public LoginResponse refreshTokens (String refreshToken) {
      try {
         var refreshJwt = jwtParser.parse(refreshToken);
         if (!TokenUtils.REFRESH_SCOPE.equals(claimString(refreshJwt, "scope"))) {
            throw new SecurityException("Token is not a refresh token");
         }
         String refreshSubject = refreshJwt.getSubject();
         String tokenRealm = claimString(refreshJwt, "realm");
         String tokenUserId = claimString(refreshJwt, "userId");
         String activeApplication = claimString(refreshJwt, "azp");
         if (refreshSubject == null || refreshSubject.isBlank() || tokenRealm == null) {
            throw new SecurityException("Refresh token is missing subject or realm authority");
         }

         String configuredRealm = envConfigUtils.getSystemRealm();
         CredentialUserIdPassword credential = credentialRepo
            .findBySubject(refreshSubject, configuredRealm, true)
            .orElseThrow(() -> new SecurityException(
               "No credential found for refresh subject: " + refreshSubject));
         String userId = credential.getUserId();
         if (tokenUserId != null && !tokenUserId.equals(userId)) {
            throw new SecurityException("Refresh-token userId does not match its credential subject");
         }

         String credentialRealm = credential.getDomainContext() != null
            ? credential.getDomainContext().getDefaultRealm()
            : null;
         if (credentialRealm == null) {
            throw new SecurityException("Credential has no home realm: " + userId);
         }

         Optional<Realm> operatingRealm = Objects.equals(credentialRealm, tokenRealm)
            ? Optional.empty()
            : realmRepo.findByRefName(tokenRealm, true, configuredRealm);
         if (!Objects.equals(credentialRealm, tokenRealm) && operatingRealm.isEmpty()) {
            throw new SecurityException("Refresh-token realm is not registered: " + tokenRealm);
         }

         Optional<UserRealmRole> realmAssignment;
         try {
            realmAssignment = userRealmRoleRepo.findActiveAssignmentForRealmWithIgnoreRules(
               userId, tokenRealm, configuredRealm);
         } catch (RuntimeException e) {
            throw new SecurityException("Unable to verify realm authorization during refresh", e);
         }
         boolean credentialRealmAuthorized = credentialAuthorizesRealm(
            securityUtils, credential, tokenRealm);
         if (!credentialRealmAuthorized) {
            throw new SecurityException("Credential is no longer authorized for realm: " + tokenRealm);
         }
         if (credentialDisabled(credential)) {
            Log.warnf("Refresh rejected: credential %s has activeStatus=%s", userId, credential.getActiveStatus());
            throw new SecurityException("Credential is no longer authorized");
         }

         Set<String> credentialRoles = credentialRolesForRealm(
            credential.getRoles(), credentialRealm, tokenRealm);
         Set<String> realmRoles = new LinkedHashSet<>();
         realmAssignment.map(UserRealmRole::getRoles).ifPresent(realmRoles::addAll);
         Set<String> userGroupRoles;

         ApplicationAuthorizationResolver.Result appAuth = ApplicationAuthorizationResolver.resolve(
            realmAssignment.map(UserRealmRole::getAuthorizedApplications).orElse(null),
            credential.getApplicationRegEx(),
            realmAssignment.map(UserRealmRole::getDefaultApplication).orElse(null),
            activeApplication);
         if (appAuth.outcome() == ApplicationAuthorizationResolver.Outcome.DENIED
               || appAuth.outcome() == ApplicationAuthorizationResolver.Outcome.AMBIGUOUS) {
            throw new SecurityException("Application authorization is no longer valid during refresh");
         }
         if (appAuth.outcome() == ApplicationAuthorizationResolver.Outcome.LEGACY) {
            // The application boundary was removed after login (login fails fast on a
            // boundary-less credential, so an active session can only get here through
            // an admin change). Refresh must not widen it back with an unscoped token.
            throw new SecurityException(
               "Credential has no application authorization (applicationRegEx unset and no per-realm "
                  + "application grant); cannot refresh an application-scoped session");
         }
         userGroupRoles = resolveUserGroupRoles(
            credential.getSubject(), tokenRealm, appAuth.activeApplication());
         Set<String> allRoles = new LinkedHashSet<>();
         allRoles.addAll(credentialRoles);
         allRoles.addAll(realmRoles);
         allRoles.addAll(userGroupRoles);

         DomainContext tokenDomainContext = Objects.equals(credentialRealm, tokenRealm)
            ? credential.getDomainContext()
            : operatingRealm.map(Realm::getDomainContext).orElse(null);
         if (tokenDomainContext == null) {
            throw new SecurityException("Realm has no data-domain configuration: " + tokenRealm);
         }

         // Only RESOLVED reaches the mint (LEGACY/AMBIGUOUS/DENIED threw above).
         String newAuthToken = TokenUtils.generateUserToken(
            refreshSubject, userId, allRoles, tokenRealm,
            tokenDomainContext.getTenantId(), tokenDomainContext.getOrgRefName(),
            tokenDomainContext.getAccountId(), appAuth.audiences(),
            appAuth.activeApplication(), credential.getRealmRegEx(),
            TokenUtils.expiresAt(durationInSeconds), issuer);
         String newRefreshToken = generateRefreshToken(
            refreshSubject, userId, tokenRealm,
            appAuth.resolved() ? appAuth.activeApplication() : null,
            newAuthToken, durationInSeconds);
         SecurityIdentity identity = validateAccessToken(newAuthToken);

         java.util.List<RoleAssignment> roleAssignments = allRoles.stream()
                 .filter(Objects::nonNull)
                 .map(role -> {
                    java.util.EnumSet<RoleSource> src = java.util.EnumSet.noneOf(RoleSource.class);
                    if (credentialRoles.contains(role)) src.add(RoleSource.CREDENTIAL);
                    if (userGroupRoles.contains(role)) src.add(RoleSource.USERGROUP);
                    if (realmRoles.contains(role)) src.add(RoleSource.REALM);
                    return new RoleAssignment(role, src);
                 })
                 .toList();

         return new LoginResponse(true,
           new LoginPositiveResponse(
              userId,
              identity,
              allRoles,
              roleAssignments,
              newAuthToken,
              newRefreshToken,
              TokenUtils.currentTimeInSecs() + durationInSeconds,
              mongodbConnectionString,
              tokenRealm,
              appAuth.resolved() ? appAuth.audiences() : null,
              appAuth.resolved() ? appAuth.activeApplication() : null));
      } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | ParseException e) {
         return new LoginResponse(false,
            new LoginNegativeResponse(null,
               400,
               401,
               e.getMessage(),
               e.getClass().getName(),
               e.toString(),
               envConfigUtils.getSystemRealm()));
      }
   }

   /**
    * A credential marked INACTIVE or DELETED must not authenticate or refresh.
    * null is legacy-allowed: rows created before the flag existed carry no
    * status, and "unknown" must not lock every existing deployment out.
    */
   static boolean credentialDisabled(CredentialUserIdPassword credential) {
      ActiveStatus status = credential.getActiveStatus();
      return status == ActiveStatus.INACTIVE || status == ActiveStatus.DELETED;
   }

   static boolean credentialAuthorizesRealm(SecurityUtils securityUtils,
                                             CredentialUserIdPassword credential,
                                             String realm) {
      Objects.requireNonNull(securityUtils, "securityUtils cannot be null");
      Objects.requireNonNull(credential, "credential cannot be null");
      if (realm == null || realm.isBlank()) {
         return false;
      }
      return securityUtils.computeAllowedRealmRefNames(credential, List.of(realm))
         .stream()
         .anyMatch(allowedRealm -> allowedRealm.equalsIgnoreCase(realm));
   }

   @Override
   public SecurityIdentity validateAccessToken (String token) {
      try {
         var jwt = jwtParser.parse(token);
         if (!TokenUtils.AUTH_SCOPE.equals(claimString(jwt, "scope"))) {
            throw new SecurityException("Token is not an access token");
         }
         String subject = jwt.getSubject();
         Set<String> roles = new HashSet<>(jwt.getGroups());

         SecurityIdentity identity = buildIdentity(subject, roles, Map.ofEntries(
            Map.entry("userId", Objects.requireNonNullElse(claimString(jwt, "userId"), subject)),
            Map.entry("realm", Objects.requireNonNullElse(claimString(jwt, "realm"), "")),
            Map.entry("tenantId", Objects.requireNonNullElse(claimString(jwt, "tenantId"), "")),
            Map.entry("orgRefName", Objects.requireNonNullElse(claimString(jwt, "orgRefName"), "")),
            Map.entry("accountNum", Objects.requireNonNullElse(claimString(jwt, "accountNum"), "")),
            Map.entry("azp", Objects.requireNonNullElse(claimString(jwt, "azp"), "")),
            Map.entry("activeApplication", Objects.requireNonNullElse(claimString(jwt, "azp"), ""))
         ));
         return identity;
      } catch (ParseException e) {
         Log.error("Token validation failed", e);
         throw new SecurityException("Invalid token");
      }
   }

   @Override
   public ProviderClaims validateTokenToClaims(String token) {
      try {
         var jwt = jwtParser.parse(token);
         if (!TokenUtils.AUTH_SCOPE.equals(claimString(jwt, "scope"))) {
            throw new SecurityException("Token is not an access token");
         }
         String subject = jwt.getSubject();
         Set<String> roles = new HashSet<>(jwt.getGroups());
         ProviderClaims.Builder b = ProviderClaims.builder(subject)
            .tokenRoles(roles)
            .issuer(issuer);
         // pass through some common attributes if present
         String username = jwt.getClaim("username");
         if (username != null) b.username(username).putAttribute("username", username);
         String email = jwt.getClaim("email");
         if (email != null) b.putAttribute("email", email);
         String iss = jwt.getIssuer();
         if (iss != null) b.putAttribute("iss", iss);
         for (String claim : List.of("userId", "realm", "tenantId", "orgRefName", "accountNum", "azp")) {
            String value = claimString(jwt, claim);
            if (value != null) b.putAttribute(claim, value);
         }
         return b.build();
      } catch (ParseException e) {
         Log.error("Token validation to claims failed", e);
         throw new SecurityException("Invalid token");
      }
   }

   private String generateRefreshToken (String subject,
                                        String userId,
                                        String realm,
                                        String activeApplication,
                                        String accessToken,
                                        long durationInSeconds) throws IOException
                                                                                                             ,
                                                                                                             NoSuchAlgorithmException, InvalidKeySpecException {

      String refreshToken = TokenUtils.generateRefreshToken(
         subject,
         userId,
         realm,
         activeApplication,
         durationInSeconds,
         issuer);

      CredentialRefreshToken refreshToken1 = CredentialRefreshToken.builder()
                                                .userId(userId)
                                                .refreshToken(refreshToken)
                                                .accessToken(accessToken)
                                                .creationDate(new Date())
                                                .lastRefreshDate(new Date())
                                                .expirationDate(new Date(System.currentTimeMillis() + (durationInSeconds * 1000) + TokenUtils.REFRESH_ADDITIONAL_DURATION_SECONDS))
                                                .build();

      //credentialRefreshTokenRepo.save(refreshToken1);


      return refreshToken;
   }

   private Optional<CredentialUserIdPassword> getCredentials (String realm, String userId) {
      return (realm == null) ? credentialRepo.findByUserId(userId) : credentialRepo.findByUserId(userId, realm);
   }

   private Set<String> resolveUserGroupRoles(String subject, String operatingRealm, String applicationId) {
      if (subject == null || subject.isBlank()) return Set.of();
      LinkedHashSet<String> roles = new LinkedHashSet<>();
      LinkedHashSet<String> directoryRealms = new LinkedHashSet<>();
      directoryRealms.add(envConfigUtils.getSystemRealm());
      if (operatingRealm != null && !operatingRealm.isBlank()) directoryRealms.add(operatingRealm);
      try {
         for (String directoryRealm : directoryRealms) {
            userProfileRepo.getBySubject(directoryRealm, subject).ifPresent(profile -> {
               List<UserGroup> groups = userGroupRepo.findByUserProfileRefWithIgnoreRules(
                  directoryRealm, profile.createEntityReference());
               if (groups == null) return;
               for (UserGroup group : groups) {
                  if (group != null && group.getRoles() != null
                        && groupAppliesToApplication(group, applicationId)) {
                     roles.addAll(group.getRoles());
                  }
               }
            });
         }
      } catch (RuntimeException e) {
         throw new IllegalStateException(
            "Unable to resolve application-scoped user-group roles for subject '"
               + subject + "' in realm '" + operatingRealm + "'", e);
      }
      return roles;
   }

   private boolean groupAppliesToApplication(UserGroup group, String applicationId) {
      if (applicationId == null || applicationId.isBlank()) return false;
      String groupApplication = group.getApplicationId();
      return groupApplication != null
         && ("*".equals(groupApplication.trim())
            || groupApplication.trim().equalsIgnoreCase(applicationId.trim()));
   }

   private SecurityIdentity buildIdentity (String userId, Set<String> roles) {
      return buildIdentity(userId, roles, Map.of());
   }

   private SecurityIdentity buildIdentity (String userId, Set<String> roles, Map<String, Object> attributes) {
      QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
      builder.setPrincipal(() -> userId);
      roles.forEach(builder::addRole);

      builder.addAttribute("token_type", "custom");
      builder.addAttribute("auth_time", System.currentTimeMillis());
      attributes.forEach(builder::addAttribute);

      return builder.build();
   }

   private String claimString(org.eclipse.microprofile.jwt.JsonWebToken jwt, String claim) {
      Object value = jwt.getClaim(claim);
      if (value == null) return null;
      String text = String.valueOf(value).trim();
      return text.isEmpty() ? null : text;
   }



}
