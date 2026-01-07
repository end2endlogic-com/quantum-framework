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

   /**
    * Find a realm by its refName (unique identifier).
    * This is used when looking up a realm for X-Realm header override to get its default DomainContext.
    * 
    * @param refName the realm's refName to search for
    * @param ignoreRules if true, bypass security rules
    * @return Optional containing the realm if found
    */
   public Optional<Realm> findByRefName(String refName, boolean ignoreRules) {
      return findByRefName(refName, ignoreRules, getSecurityContextRealmId());
   }

   /**
    * Find a realm by its refName in a specific realm database.
    * 
    * @param refName the realm's refName to search for
    * @param ignoreRules if true, bypass security rules
    * @param realmId the realm/database to search in
    * @return Optional containing the realm if found
    */
   public Optional<Realm> findByRefName(String refName, boolean ignoreRules, String realmId) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("refName", refName));
      Filter[] qfilters;
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
      } else {
         qfilters = filters.toArray(new Filter[0]);
      }

      Query<Realm> query = morphiaDataStoreWrapper.getDataStore(realmId).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

   /**
    * Find a realm by its database name (which is typically the same as the realm refName).
    * 
    * @param databaseName the database name to search for
    * @param ignoreRules if true, bypass security rules
    * @param realmId the realm/database to search in
    * @return Optional containing the realm if found
    */
   public Optional<Realm> findByDatabaseName(String databaseName, boolean ignoreRules, String realmId) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("databaseName", databaseName));
      Filter[] qfilters;
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
      } else {
         qfilters = filters.toArray(new Filter[0]);
      }

      Query<Realm> query = morphiaDataStoreWrapper.getDataStore(realmId).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

}
