package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.Realm;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RealmRepo extends MorphiaRepo<Realm> {

   public Optional<Realm> findByEmailDomain(String emailDomain) {
      return findByEmailDomain(emailDomain, false);
   }


   public Optional<Realm> findByEmailDomain(String emailDomain, boolean ignoreRules) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("emailDomain", emailDomain));
      Filter[] qfilters = new Filter[1];;
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
      } else {
         qfilters = filters.toArray(qfilters);
      }

      Query<Realm> query = morphiaDataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(qfilters);
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

      Query<Realm> query = morphiaDataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

}
