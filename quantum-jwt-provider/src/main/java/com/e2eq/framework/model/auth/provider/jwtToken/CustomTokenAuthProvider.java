package com.e2eq.framework.model.auth.provider.jwtToken;


import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;


import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialRefreshToken;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.plugins.BaseAuthProvider;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.EnvConfigUtils;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.Arrays;

@ApplicationScoped
public class CustomTokenAuthProvider extends BaseAuthProvider implements AuthProvider, UserManagement{

   @ConfigProperty(name = "auth.jwt.secret")
   String secretKey;

   @ConfigProperty(name = "auth.jwt.expiration")
   Long expirationInMinutes;

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



   // problems with permissions if you need to save
   //@Inject
   //CredentialRefreshTokenRepo credentialRefreshTokenRepo;

   public String getName () {
      return "custom";
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
      cred.setPasswordHash(EncryptionUtils.hashPassword(newPassword));
      cred.setForceChangePassword(forceChangePassword);
      cred.setAuthProviderName(getName());
      credentialRepo.save( cred);
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
         String alg = credential.getHashingAlgorithm();
         if (alg != null && (alg.equalsIgnoreCase("BCrypt.default") || alg.toLowerCase().startsWith("bcrypt") || alg.equals(EncryptionUtils.hashAlgorithm()))) {
            credential.setPasswordHash(EncryptionUtils.hashPassword(password));
         } else {
            throw new SecurityException("Unsupported hashing algorithm: " + alg);
         }
         credential.setDomainContext(domainContext);
         credential.setRoles(roles.toArray(new String[roles.size()]));
         credential.setLastUpdate(new Date());
         credential.setAuthProviderName(getName());
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
         String alg = credential.getHashingAlgorithm();
         if (alg != null && (alg.equalsIgnoreCase("BCrypt.default") || alg.toLowerCase().startsWith("bcrypt") || alg.equals(EncryptionUtils.hashAlgorithm()))) {
            credential.setPasswordHash(EncryptionUtils.hashPassword(password));
         } else {
            throw new SecurityException("Unsupported hashing algorithm: " + alg);
         }
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
         credentialRepo.save(credential);

      }
      return subject;
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
      try {
         Optional<CredentialUserIdPassword> ocredential = getCredentials(envConfigUtils.getSystemRealm(), userId);

         if (ocredential.isPresent()) {
            CredentialUserIdPassword credential = ocredential.get();
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
                  Set<String> groups = new HashSet<>(Arrays.asList(credential.getRoles()));
                  String authToken = TokenUtils.generateUserToken(
                     credential.getSubject(),
                     groups,
                     TokenUtils.expiresAt(durationInSeconds),
                     issuer);

                  String refreshToken = generateRefreshToken(credential.getUserId(), authToken,
                     TokenUtils.currentTimeInSecs() + durationInSeconds + TokenUtils.REFRESH_ADDITIONAL_DURATION_SECONDS);
                  SecurityIdentity identity = validateAccessToken(authToken);
                  Set<String> rolesSet = new HashSet<>(Arrays.asList(credential.getRoles()));

                  return new LoginResponse(
                     true,
                     new LoginPositiveResponse(
                        userId,
                        identity,
                        rolesSet,
                        authToken,
                        refreshToken,
                        TokenUtils.currentTimeInSecs() + durationInSeconds,
                        mongodbConnectionString,
                        envConfigUtils.getSystemRealm())
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

   @Override
   public LoginResponse refreshTokens (String refreshToken) {
      SecurityIdentity identity = validateAccessToken(refreshToken);
      String userId = identity.getPrincipal().getName();
      String newAuthToken = generateAuthToken(userId);
      String newRefreshToken = null;
      try {
         newRefreshToken = generateRefreshToken(userId, newAuthToken, durationInSeconds);
         return new LoginResponse(true,
           new LoginPositiveResponse(
              userId,
              identity,
              identity.getRoles(),
              newAuthToken,
              newRefreshToken,
              TokenUtils.currentTimeInSecs() + durationInSeconds,
              mongodbConnectionString,
              systemRealm));
      } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
         return new LoginResponse(false,
            new LoginNegativeResponse(userId,
               400,
               500,
               e.getMessage(),
               e.getClass().getName(),
               e.toString(),
               envConfigUtils.getSystemRealm()));
      }
   }

   @Override
   public SecurityIdentity validateAccessToken (String token) {
      try {
         var jwt = jwtParser.parse(token);
         String userId = jwt.getSubject();
         Set<String> roles = new HashSet<>(jwt.getGroups());

         SecurityIdentity identity = buildIdentity(userId, roles);
         return identity;
      } catch (ParseException e) {
         Log.error("Token validation failed", e);
         throw new SecurityException("Invalid token");
      }
   }

   private String generateAuthToken (String userId) {
      try {
         Date now = new Date();
         Date expiration = new Date(now.getTime() + (expirationInMinutes * 60 * 1000));

         String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
         String payload =
            Base64.getUrlEncoder().encodeToString(("{\"sub\":\"" + userId + "\",\"iat\":" + now.getTime() / 1000 + "," +
                                                      "\"exp\":" + expiration.getTime() / 1000 + "}").getBytes());

         String signature = sign(header + "." + payload, secretKey);

         return header + "." + payload + "." + signature;
      } catch (Exception e) {
         throw new RuntimeException("Failed to generate auth token", e);
      }
   }

   private String sign (String data, String secret) throws Exception {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
      mac.init(secretKeySpec);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes()));
   }

   private String generateRefreshToken (String userId, String accessToken, long durationInSeconds) throws IOException
                                                                                                             ,
                                                                                                             NoSuchAlgorithmException, InvalidKeySpecException {

      String refreshToken = TokenUtils.generateRefreshToken(
         userId,
         TokenUtils.currentTimeInSecs() + durationInSeconds + TokenUtils.REFRESH_ADDITIONAL_DURATION_SECONDS,
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

   private SecurityIdentity buildIdentity (String userId, Set<String> roles) {
      QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
      builder.setPrincipal(() -> userId);
      roles.forEach(builder::addRole);

      builder.addAttribute("token_type", "custom");
      builder.addAttribute("auth_time", System.currentTimeMillis());

      return builder.build();
   }



}
