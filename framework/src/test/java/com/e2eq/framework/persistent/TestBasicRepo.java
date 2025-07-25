package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoCursor;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TestBasicRepo extends BaseRepoTest{
   @Inject
   MorphiaDataStore dataStore;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   SecurityUtils securityUtils;


   @Test
   public void testUserProfileRepo() {
      PrincipalContext pContext = securityUtils.getSystemPrincipalContext();
      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         Optional<UserProfile> oup = userProfileRepo.getByUserId(securityUtils.getSystemUserId());
         assertTrue(oup.isPresent());
      }
   }


   @Test
   public void testUserProfileFilteredQuery() {
      PrincipalContext pContext = securityUtils.getSystemPrincipalContext();
      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         Filter x = MorphiaUtils.convertToFilter("userId:" + securityUtils.getSystemUserId(), UserProfile.class);
         Query<UserProfile> q = dataStore.getDefaultSystemDataStore().find(UserProfile.class);
         MongoCursor<UserProfile> cursor = q.filter(x).iterator(new FindOptions().skip(0).limit(10));

         List<UserProfile> list = new ArrayList<>();
         cursor.forEachRemaining((u) -> list.add(u));
         assertTrue(!list.isEmpty());
      }
   }

   protected UserProfile ensureUserProfile(Datastore ds, String userId, String[] roles, String password) {

      UserProfile userProfile = null;

      try ( MorphiaSession session = ds.startSession();) {
         session.startTransaction();

         Optional<UserProfile> ouserProfile = userProfileRepo.getByUserId(ds,userId);

         if (!ouserProfile.isPresent()) {
           userProfile = UserProfile.builder()
                    .refName(userId)
                    .email(userId)
                    .displayName("Test User")
                    .username(userId)
                    .userId(userId)
                   .dataDomain(testUtils.getTestDataDomain())
                    .build();
            userProfile = userProfileRepo.createUser(ds,
                    userProfile,null, roles,password );
         } else {
            userProfile = ouserProfile.get();
         }
         session.commitTransaction();
      }

      return userProfile;

   }

   @Test
   public void testTransactions() {
      Log.infof("TestTransactions: Realm: %s",testUtils.getTestRealm());
      Datastore ds = dataStore.getDataStore(testUtils.getTestRealm());


      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         ensureUserProfile(ds, securityUtils.getTestUserId(), roles, "test123xxxxxxx");
         try (MorphiaSession session = ds.startSession()) {
            session.startTransaction();
            Optional<UserProfile> u = userProfileRepo.findByRefName(ds,securityUtils.getTestUserId());
            Assertions.assertTrue(u.isPresent());
            userProfileRepo.findById(session, u.get().getId());
            session.commitTransaction();
         }
      }
   }

   @Test
   public void testMerge() throws ReferentialIntegrityViolationException {

      // Create a test user
      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         // see if the test user is already there  ( dirty database )
         Optional<UserProfile> u = userProfileRepo.findByRefName("tuser@test-system.com");
         if (u.isPresent()) {
            userProfileRepo.delete(u.get());
         }

         UserProfile userProfile = new UserProfile();
         userProfile.setRefName("tuser@test-system.com");
         userProfile.setEmail("tuser@test-system.com");
         userProfile.setDisplayName("Test");
         userProfile.setUsername("tuser@test-system.com");
         userProfile.setUserId("tuser@test-system.com");
         userProfile.setDataDomain(testUtils.getTestDataDomain());
         userProfile = userProfileRepo.save(userProfile);
         assertTrue(userProfile.getId()!= null);

         UserProfile userProfile2 = new UserProfile();
         // so in the end we will need to look up the id, and the version any way so its debatable if
         // using the built in merge is worth it, as the record will need to be read twice for every merge.
         userProfile2.setId(userProfile.getId());
         userProfile2.setVersion(userProfile.getVersion()); // need to deal with this somehow.

         // if this is the only thing we want to change
         userProfile2.setUsername("changed");
         userProfile2.setSkipValidation(true);
         userProfileRepo.merge(userProfile2);

         UserProfile dbProfile = userProfileRepo.findById(userProfile.getId()).get();
         assertTrue(dbProfile.getUsername().equals("changed"));
         assertTrue(dbProfile.getUserId().equals("tuser@test-system.com"));
         assertTrue(dbProfile.getDisplayName().equals("Test"));
         userProfileRepo.delete(dbProfile);

      }
   }


   // @Test
   // Commented out because the reference data does not have a user registered thus this fails
   // the functionality his covered however in the previous test
   public void testRegistrationFilteredQuery() {

      Filter x = MorphiaUtils.convertToFilter("userId:tuser@test-system.com", ApplicationRegistration.class);
      Query<ApplicationRegistration> q = dataStore.getDefaultSystemDataStore().find(ApplicationRegistration.class);
      MongoCursor<ApplicationRegistration> cursor = q.filter(x).iterator(new FindOptions().skip(0).limit(10));

      List<ApplicationRegistration> list = new ArrayList<>();
      cursor.forEachRemaining( (u) -> list.add(u));
      assertTrue(!list.isEmpty());
   }
}
