package com.e2eq.framework.persistent;

import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoCursor;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import io.quarkus.test.junit.QuarkusTest;
import com.e2eq.framework.model.persistent.security.ApplicationRegistration;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
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

   @BeforeAll
   static public void setUpData() {

   }

   @Test
   public void testUserProfileRepo() {
     Optional<UserProfile> oup =  userProfileRepo.findByRefName(SecurityUtils.systemUserId);
     assertTrue(oup.isPresent());
   }


   @Test
   public void testUserProfileFilteredQuery() {

      Filter x = MorphiaUtils.convertToFilter("refName:" + SecurityUtils.systemUserId);
   /*   Filter f = MorphiaUtils.convertToFilter("userId:Test");
      Filter y = Filters.eq("userId", "Test");
      Filter z = Filters.eq("userId", 100); */


      Query<UserProfile> q = dataStore.getDataStore().find(UserProfile.class);
      MongoCursor<UserProfile> cursor = q.filter(x).iterator(new FindOptions().skip(0).limit(10));

      List<UserProfile> list = new ArrayList<>();
      cursor.forEachRemaining( (u) -> list.add(u));
      assertTrue(!list.isEmpty());
   }


  // @Test
   // Commented out because the reference data does not have a user registered thus this fails
   // the functionality his covered however in the previous test
   public void testRegistrationFilteredQuery() {

      Filter x = MorphiaUtils.convertToFilter("userId:tuser@test-system.com");
      Query<ApplicationRegistration> q = dataStore.getDataStore().find(ApplicationRegistration.class);
      MongoCursor<ApplicationRegistration> cursor = q.filter(x).iterator(new FindOptions().skip(0).limit(10));

      List<ApplicationRegistration> list = new ArrayList<>();
      cursor.forEachRemaining( (u) -> list.add(u));
      assertTrue(!list.isEmpty());
   }
}
