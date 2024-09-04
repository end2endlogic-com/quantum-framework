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
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("emailDomain", emailDomain));
      Filter[] qfilters = getFilterArray(filters);

      Query<Realm> query = dataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

   public Optional<Realm> findByTenantId(String tenantId) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("tenantId", tenantId));
      Filter[] qfilters = getFilterArray(filters);

      Query<Realm> query = dataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(qfilters);
      Realm obj = query.first();
      return Optional.ofNullable(obj);
   }

}
