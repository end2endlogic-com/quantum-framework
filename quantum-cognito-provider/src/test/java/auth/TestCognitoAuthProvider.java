package auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.wildfly.common.Assert;

import java.util.Optional;
import java.util.Set;


@QuarkusTest
public class TestCognitoAuthProvider extends BaseRepoTest{
   @ConfigProperty(name = "auth.provider")
   String authProvider;

   @Inject
   AuthProviderFactory authProviderFactory;

   @ConfigProperty(name = "quantum.realmConfig.testUserId")
   String testUserId;

   @ConfigProperty(name = "test.password")
   String testPassword;

   @Inject
   TestUtils testUtils;
   @Inject
   CredentialRepo credentialRepo;

   @Inject
   UserProfileRepo userProfileRepo;



   @Test
   public void testCreateTestUser() throws ReferentialIntegrityViolationException {
      AuthProvider authProvider = authProviderFactory.getAuthProvider();
      UserManagement userManager = authProviderFactory.getUserManager();

      try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
         if (userManager.userIdExists(testUserId)) {
            userManager.removeUserWithUserId(testUserId);
         }

         userManager.createUser(testUserId,
            testPassword,
            Boolean.FALSE, Set.of("user"),
            DomainContext.builder()
                  .orgRefName(testUtils.getTestOrgRefName())
               .defaultRealm(testUtils.getTestRealm())
               .tenantId(testUtils.getTestTenantId())
               .accountId(testUtils.getTestAccountNumber())
               .build());

      }
   }

   //@Test
   public void testGetRoles() {
      if (authProvider.equals("cognito")) {
         UserManagement userManager = authProviderFactory.getUserManager();
         Set<String> roles = userManager.getUserRolesForSubject("56d9c248-982c-4080-9817-ffe09d39ecc9" );
         Assert.assertTrue(roles.contains("user"));
      }
   }

   @Test
   public void testCreateCognitoUser() throws ReferentialIntegrityViolationException {
      if (authProvider.equals("cognito")) {
         AuthProvider authProvider = authProviderFactory.getAuthProvider();
         UserManagement userManager = authProviderFactory.getUserManager();

         try (final SecuritySession s = new SecuritySession(pContext, rContext)) {


            Optional<CredentialUserIdPassword> ocred = credentialRepo.findByUserId(testUserId);
            if (ocred.isPresent()) {
               if (!userManager.removeUserWithUserId(testUserId)) {
                  Log.warnf("User not removed in cognito  with userid:%s in credentials collection in  realm: %s may be stale", ocred.get().getUserId(), credentialRepo.getDatabaseName());
               }
               credentialRepo.findByUserId(testUserId).ifPresent(existing -> {
                  try {
                     credentialRepo.delete(existing);
                  } catch (ReferentialIntegrityViolationException e) {
                     throw new RuntimeException(e);
                  }
               });
            }

            DomainContext domainContext = DomainContext.builder()
                                             .orgRefName(testUtils.getTestOrgRefName())
                                             .defaultRealm(testUtils.getTestRealm())
                                             .accountId(testUtils.getTestAccountNumber())
                                             .tenantId(testUtils.getTestTenantId())
                                             .build();

            String subject = userManager.createUser(testUserId, testPassword, Boolean.FALSE,  Set.of("user", "admin"), domainContext);
            Assert.assertTrue(userManager.userIdExists(testUserId));
            Optional<CredentialUserIdPassword> createdCred = credentialRepo.findByUserId(testUserId);
            Assert.assertTrue(createdCred.isPresent());
            Assertions.assertEquals(subject, createdCred.get().getSubject());

            Set<String> roles = userManager.getUserRolesForUserId(testUserId);
            Assert.assertTrue(roles.contains("user"));
            AuthProvider.LoginResponse response = authProvider.login(testUserId, testPassword);
            Assert.assertTrue(response.authenticated());
            userManager.assignRolesForUserId(testUserId, Set.of("admin"));
            Assert.assertTrue(userManager.getUserRolesForUserId(testUserId).contains("admin"));
            userManager.removeRolesForUserId(testUserId, Set.of("admin", "user"));
            Assert.assertFalse(userManager.getUserRolesForUserId(testUserId).contains("admin"));
            //userManager.removeUser(testUserId);
            //Assert.assertFalse(userManager.userExists(testUserId));
         }
      } else {
         Log.infof("Test skipped for auth provider: {%s}", authProvider);
      }
   }
}
