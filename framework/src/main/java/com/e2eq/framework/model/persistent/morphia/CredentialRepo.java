package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CredentialRepo extends MorphiaRepo<CredentialUserIdPassword> {

   //@Override
   //public String getRealmId () {
   //   return DefaultRealm;
   //}

   public Optional<CredentialUserIdPassword> findByUserId(@NotNull String userId)
   {
      return findByUserId(getSecurityContextRealmId(), userId);
   }

   public Optional<CredentialUserIdPassword> findByUserId(@NotNull String realmId, @NotNull String userId) {
      return findByUserId(realmId, userId, true);
   }

   public Optional<CredentialUserIdPassword> findByUserId(@NotNull String realmId, @NotNull String userId, boolean ignoreRules) {
      if (userId == null) {
         throw new IllegalArgumentException("parameter refId can not be null");
      }
      if (realmId == null) {
         throw new IllegalArgumentException("parameter realmId can not be null");
      }

      Datastore ds = dataStore.getDataStore(realmId);
      if (Log.isDebugEnabled()) {
         Log.debug("DataStore: dataBaseName:" + ds.getDatabase().getName() + " retreived via realmId:" + realmId);
      }

      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("userId", userId));
      // Add filters based upon rule and resourceContext;
      Filter[] qfilters = new Filter[1];
      if (!ignoreRules) {
         qfilters = getFilterArray(filters);
      } else {
         qfilters = filters.toArray(qfilters);
      }

      Query<CredentialUserIdPassword> query = ds.find(CredentialUserIdPassword.class).filter(qfilters);
      //Query<CredentialUserIdPassword> query = dataStore.getDataStore(getRealmId()).find(getPersistentClass()).filter(qfilters);
      CredentialUserIdPassword obj = query.first();

      /**
       This is what the query should look like:

       $and:[ {"userId": "mingardia@end2endlogic.com"},
              {$or:[
                    {"dataDomain.ownerId": "system@b2bintegrator.com"},
                    {"dataDomain.ownerId": "mingardia@end2endlogic.com"}
                   ]
              }
            ],
       "_t":{"$in": ["CredentialUserIdPassword"]}}
       */


      return Optional.ofNullable(obj);
   }
}
