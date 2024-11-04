package com.e2eq.framework.persistent;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import com.mongodb.client.MongoCursor;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.test.junit.QuarkusTest;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TestBasicRepo {
   @Inject
   MorphiaDataStore dataStore;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   RuleContext ruleContext;

   @BeforeAll
   static public void setUpData() {

   }

   @Test
   public void testUserProfileRepo() {
      String[] roles = {"user"};
      PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
      ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "view");
      TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         Optional<UserProfile> oup = userProfileRepo.findByRefName(SecurityUtils.systemUserId);
         assertTrue(oup.isPresent());
      }
   }


   @Test
   public void testUserProfileFilteredQuery() {

      String[] roles = {"user"};
      PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
      ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "view");
      TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         Filter x = MorphiaUtils.convertToFilter("refName:" + SecurityUtils.systemUserId);

         Query<UserProfile> q = dataStore.getDefaultSystemDataStore().find(UserProfile.class);
         MongoCursor<UserProfile> cursor = q.filter(x).iterator(new FindOptions().skip(0).limit(10));

         List<UserProfile> list = new ArrayList<>();
         cursor.forEachRemaining((u) -> list.add(u));
         assertTrue(!list.isEmpty());
      }
   }

   @Test
   public void testTransactions() {
      Datastore ds = dataStore.getDefaultSystemDataStore();
      String[] roles = {"user"};
      PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
      ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "view");
      TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
      try(final SecuritySession s = new SecuritySession(pContext, rContext)) {
         try (MorphiaSession session = ds.startSession()) {
            session.startTransaction();
            Optional<UserProfile> u = userProfileRepo.findByRefName(SecurityUtils.systemUserId);
            Assertions.assertTrue(u.isPresent());
            userProfileRepo.findById(session, u.get().getId());
            session.commitTransaction();
         }
      }
   }

   @Test
   public void testMerge() {
      Datastore ds = dataStore.getDefaultSystemDataStore();
      String[] roles = {"user"};
      PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.userId, roles);
      ResourceContext rContext = TestUtils.getResourceContext(TestUtils.area, "userProfile", "save");
      TestUtils.initRules(ruleContext, "security","userProfile", TestUtils.userId);
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
         userProfile.setUserName("tuser@test-system.com");
         userProfile.setUserId("tuser@test-system.com");
         userProfile.setDataDomain(TestUtils.dataDomain);
         userProfile = userProfileRepo.save(userProfile);
         assertTrue(userProfile.getId()!= null);

         UserProfile userProfile2 = new UserProfile();
         // so in the end we will need to look up the id, and the version any way so its debatable if
         // using the built in merge is worth it, as the record will need to be read twice for every merge.
         userProfile2.setId(userProfile.getId());
         userProfile2.setVersion(userProfile.getVersion()); // need to deal with this somehow.

         // if this is the only thing we want to change
         userProfile2.setUserName("changed");
         userProfile2.setSkipValidation(true);
         userProfileRepo.merge(userProfile2);

         UserProfile dbProfile = userProfileRepo.findById(userProfile.getId()).get();
         assertTrue(dbProfile.getUserName().equals("changed"));
         assertTrue(dbProfile.getUserId().equals("tuser@test-system.com"));
         assertTrue(dbProfile.getDisplayName().equals("Test"));
         userProfileRepo.delete(dbProfile);

      }
   }


   // @Test
   // Commented out because the reference data does not have a user registered thus this fails
   // the functionality his covered however in the previous test
   public void testRegistrationFilteredQuery() {

      Filter x = MorphiaUtils.convertToFilter("userId:tuser@test-system.com");
      Query<ApplicationRegistration> q = dataStore.getDefaultSystemDataStore().find(ApplicationRegistration.class);
      MongoCursor<ApplicationRegistration> cursor = q.filter(x).iterator(new FindOptions().skip(0).limit(10));

      List<ApplicationRegistration> list = new ArrayList<>();
      cursor.forEachRemaining( (u) -> list.add(u));
      assertTrue(!list.isEmpty());
   }
}
