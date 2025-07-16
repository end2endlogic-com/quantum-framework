package com.e2eq.framework.model.security.auth.provider.jwtToken;


import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.security.CredentialRefreshToken;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.UserManagement;
import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TokenUtils;
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
import java.util.regex.Pattern;

@ApplicationScoped
public class CustomTokenAuthProvider  extends BaseAuthProvider implements AuthProvider, UserManagement{

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

   @Inject
   JWTParser jwtParser;

   // problems with permissions if you need to save
   //@Inject
   //CredentialRefreshTokenRepo credentialRefreshTokenRepo;

   public String getName () {
      return "custom";
   }



   @Override
   public void changePassword(String userId, String oldPassword, String newPassword, Boolean forceChangePassword) {
      changePassword(securityUtils.getSystemRealm(), userId, oldPassword, newPassword, forceChangePassword);
   }


   @Override
   public void changePassword(String realm, String userId, String oldPassword, String newPassword, Boolean forceChangePassword) {
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId, realm);
      if (!ocred.isPresent()) {
         throw new NotFoundException(String.format("User with userId %s not found in realm %s", userId, realm));
      }

      CredentialUserIdPassword cred = ocred.get();
      if (!EncryptionUtils.hashPassword(oldPassword).equals(cred.getPasswordHash())) {
         throw new SecurityException("Old Password was incorrect");
      }
      cred.setPasswordHash(EncryptionUtils.hashPassword(newPassword));
      cred.setForceChangePassword(forceChangePassword);
      credentialRepo.save(realm, cred);
   }

   @Override
   public void createUser (String userId, String password, String username, Set<String> roles,
                           DomainContext domainContext) throws SecurityException {
      createUser(securityUtils.getSystemRealm(), userId, password, null, username, roles, domainContext);
   }

   @Override
   public void createUser (String userId, String password, Boolean forceChangePassword, String username, Set<String> roles,
                           DomainContext domainContext) throws SecurityException {
      createUser(securityUtils.getSystemRealm(), userId, password, forceChangePassword, username, roles, domainContext);
   }


   @Override
   public void createUser (String realm, String userId, String password, String username, Set<String> roles, DomainContext domainContext) throws SecurityException {
      createUser(realm, userId, password, null, username, roles, domainContext);
   }

   @Override
   public void createUser (String realm, String userId, String password, Boolean forceChangePassword, String username
      , Set<String> roles, DomainContext domainContext) throws SecurityException {
      Objects.requireNonNull(realm, "Realm cannot be null");
      Objects.requireNonNull(userId, "UserId cannot be null");
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(password, "Password cannot be null");
      Objects.requireNonNull(domainContext, "DomainContext cannot be null");

      try {
         byte[] utf8Bytes = password.getBytes("UTF-8");
         int byteLength = utf8Bytes.length;

         if (byteLength > 72) { // Limit username to 17 characters due to Bcrypt limitations
            throw new SecurityException("Username too long must be smaller than 17 characters");
         }
      } catch (UnsupportedEncodingException ex) {
         throw new SecurityException("Security exception: " + ex.getMessage());
      }
      Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(userId, realm);
      if (ocred.isPresent()) {
         if (!ocred.get().getUsername().equalsIgnoreCase(username)) {
            throw new SecurityException(String.format("User with the same userId:%s already exists with different " +
                                                         "username:%s, than given username:%s", userId,
               ocred.get().getUsername(), username));
         }
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
         throw new SecurityException(String.format("User with userId:%s and username:%s already exists with same " +
                                                      "domain context, and roles", userId, username));
      } else {

         CredentialUserIdPassword credential = new CredentialUserIdPassword();
         credential.setUserId(userId);
         credential.setUsername(username);
         credential.setForceChangePassword(forceChangePassword);
         if (credential.getHashingAlgorithm().equalsIgnoreCase("BCrypt.default")) {
            credential.setPasswordHash(EncryptionUtils.hashPassword(password));
         } else {
            throw new SecurityException("Unsupported hashing algorithm: " + credential.getHashingAlgorithm());
         }
         credential.setDomainContext(domainContext);
         credential.setRoles(roles.toArray(new String[roles.size()]));
         credential.setLastUpdate(new Date());

         credentialRepo.save(realm, credential);
      }

   }


   @Override
   public boolean removeUserWithUserId (String userId) throws ReferentialIntegrityViolationException {
      return removeUserWithUserId(securityUtils.getSystemRealm(), userId);
   }

   @Override
   public boolean removeUserWithUserId (String realm, String userId) throws ReferentialIntegrityViolationException {
      Objects.requireNonNull(realm, "Realm cannot be null");
      Objects.requireNonNull(userId, "UserId cannot be null");
      Optional<CredentialUserIdPassword> ocredentialUserIdPassword = credentialRepo.findByUserId(userId, realm);
      if (ocredentialUserIdPassword.isPresent()) {
         long val = credentialRepo.delete(realm, ocredentialUserIdPassword.get());
         if (val != 0)
            return true;
         else
            return false;
      }
      return false;
   }



   @Override
   public boolean removeUserWithUsername (String username) throws ReferentialIntegrityViolationException {
      return removeUserWithUsername(securityUtils.getSystemRealm(), username);
   }

   @Override
   public boolean removeUserWithUsername (String realm, String username) throws ReferentialIntegrityViolationException {
      Objects.requireNonNull(realm, "Realm cannot be null");
      Objects.requireNonNull(username, "Username cannot be null");
      Optional<CredentialUserIdPassword> ocredentialUserIdPassword = credentialRepo.findByUsername(username, realm);
      if (ocredentialUserIdPassword.isPresent()) {
         long val = credentialRepo.delete(realm, ocredentialUserIdPassword.get());
         if (val != 0)
            return true;
         else
            return false;
      }
      return false;
   }

   @Override
   public void assignRoles (String username, Set<String> roles) throws SecurityException {
      Objects.requireNonNull(username, "Username cannot be null");
      assignRoles(securityUtils.getSystemRealm(), username, roles);
   }

   @Override
   public void assignRoles (String realm, String username, Set<String> roles) throws SecurityException {
      Objects.requireNonNull(realm, "Realm cannot be null");
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");

      credentialRepo.findByUsername(username, realm).ifPresentOrElse(credential -> {
         Set<String> existingRoles = new HashSet<>(Arrays.asList(credential.getRoles()));
         existingRoles.addAll(roles);
         credential.setRoles(existingRoles.toArray(new String[existingRoles.size()]));
         credentialRepo.save(realm, credential);
      }, () -> {
         throw new SecurityException("User not found: " + username);
      });
   }


   @Override
   public void removeRoles (String username, Set<String> roles) throws SecurityException {
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");
      if (roles.isEmpty()) {
         throw new IllegalArgumentException("Roles cannot be empty");
      }
      removeRoles(securityUtils.getSystemRealm(), username, roles);
   }

   @Override
   public void removeRoles (String realm, String username, Set<String> roles) throws SecurityException {
      Objects.requireNonNull(realm, "Realm cannot be null");
      Objects.requireNonNull(username, "Username cannot be null");
      Objects.requireNonNull(roles, "Roles cannot be null");
      if (roles.isEmpty()) {
         throw new IllegalArgumentException("Roles cannot be empty");
      }
      credentialRepo.findByUsername(username, realm).ifPresentOrElse(
         credential -> {
            Set<String> existingRoles = new HashSet<>(Arrays.asList(credential.getRoles()));
            existingRoles.removeAll(roles);
            credential.setRoles(existingRoles.toArray(new String[existingRoles.size()]));
            credentialRepo.save(realm, credential);
         }, () -> {
            throw new SecurityException("User not found: " + username);
         });

   }


   @Override
   public Set<String> getUserRoles (String username) throws SecurityException {
      return getUserRoles(securityUtils.getSystemRealm(), username);
   }

   @Override
   public Set<String> getUserRoles (String realm, String username) throws SecurityException {
      Set<String> rolesHolder = new HashSet<>();

      credentialRepo.findByUsername(username, realm).ifPresentOrElse(
         credential -> {
            rolesHolder.addAll(Arrays.asList(credential.getRoles()));
         },
         () -> {
            throw new SecurityException("User not found: " + username);
         }
      );

      return rolesHolder;


        /* old code can be removed
        final Set<String>[] rolesHolder = new Set[1];

        credentialRepo.findByUserId(username).ifPresentOrElse(
            credential -> {
                rolesHolder[0] = new HashSet<>(Arrays.asList(credential.getRoles()));
            },
            () -> {
                throw new SecurityException("User not found: " + username);
            }
        );

        return rolesHolder[0]; */
   }

   @Override
   public boolean usernameExists (String username) throws SecurityException {
      return usernameExists(securityUtils.getSystemRealm(), username);
   }

   @Override
   public boolean usernameExists (String realm, String username) throws SecurityException {
      return credentialRepo.findByUsername(username, realm).isPresent();
   }


   @Override
   public boolean userIdExists (String userId) throws SecurityException {
      return userIdExists(securityUtils.getSystemRealm(), userId);
   }

   @Override
   public boolean userIdExists (String realm, String userId) throws SecurityException {
      return credentialRepo.findByUserId(userId, realm).isPresent();
   }


   @Override
   public LoginResponse login (String userId, String password) {
      return login(securityUtils.getSystemRealm(), userId, password);
   }

   @Override
   public LoginResponse login (String realm, String userId, String password) {
      try {
         Optional<CredentialUserIdPassword> ocredential = getCredentials(realm, userId);

         if (ocredential.isPresent()) {
            CredentialUserIdPassword credential = ocredential.get();
            if (credential.getForceChangePassword() != null && credential.getForceChangePassword()) {
               return new LoginResponse(false,
                  new LoginNegativeResponse(userId,
                     400,
                     403,
                     "Password change required",
                     "PasswordChangeRequired",
                     "Password change required")
               );
            }
            if (credential.getHashingAlgorithm().equalsIgnoreCase("BCrypt.default")) {
               boolean isCredentialValid = EncryptionUtils.checkPassword(password, credential.getPasswordHash());
               if (isCredentialValid) {
                  // String authToken = generateAuthToken(userId);
                  Set<String> groups = new HashSet<>(Arrays.asList(credential.getRoles()));
                  String authToken = TokenUtils.generateUserToken(
                     credential.getUsername(),
                     groups,
                     TokenUtils.expiresAt(durationInSeconds),
                     issuer);

                  String refreshToken = generateRefreshToken(credential.getUserId(), authToken,
                     TokenUtils.currentTimeInSecs() + durationInSeconds + TokenUtils.REFRESH_ADDITIONAL_DURATION_SECONDS);
                  SecurityIdentity identity = validateAccessToken(authToken);
                  Set<String> rolesSet = new HashSet<>(Arrays.asList(credential.getRoles()));

                  return new LoginResponse(
                     true,
                     new LoginPositiveResponse(userId,
                        identity, rolesSet, authToken, refreshToken, TokenUtils.currentTimeInSecs() + durationInSeconds)
                  );
               } else {
                  return new LoginResponse(false,
                     new LoginNegativeResponse(userId,
                        400,
                        401,
                        "Invalid credentials",
                        "Invalid credentials",
                        "credentials did not match")
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
                  "Credentials not found")
            );
         }
      } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
         return new LoginResponse(false,
            new LoginNegativeResponse(userId,
               400,
               500,
               e.getMessage(),
               e.getClass().getName(),
               e.toString()
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
            new LoginPositiveResponse(userId,
               identity,
               identity.getRoles(),
               newAuthToken,
               newRefreshToken,
               TokenUtils.currentTimeInSecs() + durationInSeconds));
      } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
         return new LoginResponse(false,
            new LoginNegativeResponse(userId,
               400,
               500,
               e.getMessage(),
               e.getClass().getName(),
               e.toString()));
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
