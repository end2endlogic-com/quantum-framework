package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Realm;
import dev.morphia.MorphiaDatastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RealmRepo extends MorphiaRepo<Realm> {


   public java.util.List<Realm> getAllListWithIgnoreRules (String realmId) {
      MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realmId);
      String dataBase = ds.getDatabase().getName();
      String collectionName = ds.getMapper().getEntityModel(Realm.class).collectionName();
      dev.morphia.query.MorphiaCursor<Realm> cursor = ds.find(getPersistentClass()).iterator();
      List<Realm> result;
      try (cursor) {
          result = cursor.toList();
          return result;
      }
   }

   public Optional<Realm> findByEmailDomain(String emailDomain) {
      return findByEmailDomain(emailDomain, false);
   }

   public Optional<Realm> findByEmailDomain(String emailDomain, boolean ignoreRules) {
      return findByEmailDomain(emailDomain, ignoreRules, getSecurityContextRealmId());
   }


   public Optional<Realm> findByEmailDomain(String emailDomain, boolean ignoreRules, String realm) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("emailDomain", emailDomain));
      Filter[] qfilters = new Filter[1];;
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
      } else {
         qfilters = filters.toArray(qfilters);
      }

      Query<Realm> query = morphiaDataStoreWrapper.getDataStore(realm).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

   public Optional<Realm> findByTenantId(String tenantId, boolean ignoreRules) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("databaseName", tenantId));
      Filter[] qfilters = new Filter[1];;
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
      } else {
         qfilters = filters.toArray(qfilters);
      }

      Query<Realm> query = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

}
