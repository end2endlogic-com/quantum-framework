package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.base.CloseableIterator;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.checkerframework.checker.units.qual.C;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CredentialRepo extends MorphiaRepo<CredentialUserIdPassword> {
   @Inject
   SecurityUtils securityUtils;

   @Override
   public String getDatabaseName () {
      //return morphiaDataStore.getDataStore(securityUtils.getSystemRealm()).getDatabase().getName();
      return securityUtils.getSystemRealm();
   }


   public Optional<CredentialUserIdPassword> findByUserId(@NotNull String userId)
   {
      return findByUserId( userId, securityUtils.getSystemRealm());
   }

   public Optional<CredentialUserIdPassword> findByUsername(@NotNull String username)
   {
      return findByUsername( username, securityUtils.getSystemRealm());
   }


   public Optional<CredentialUserIdPassword> findByUsername(  @NotNull String username, @NotNull String realmId) {
      return findByUsername( username,realmId, false);
   }


   public Optional<CredentialUserIdPassword> findByUserId( @NotNull String userId,  @NotNull String realmId) {
      return findByUserId( userId, realmId,false);
   }

   public Optional<CredentialUserIdPassword> findByUsername( @NotNull String username, @NotNull String realmId, boolean ignoreRules) {
      if (username == null) {
         throw new IllegalArgumentException("parameter refId can not be null");
      }
      if (realmId == null) {
         throw new IllegalArgumentException("parameter realmId can not be null");
      }

      Datastore ds = morphiaDataStore.getDataStore(realmId);
      if (Log.isDebugEnabled()) {
         Log.debug("DataStore: dataBaseName:" + ds.getDatabase().getName() + " retrieved via realmId:" + realmId);
      }

      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("username", username));
      // Add filters based upon rule and resourceContext;
      Filter[] qfilters = new Filter[1];
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
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

   public Optional<CredentialUserIdPassword> findByUserId( @NotNull String userId, @NotNull String realmId, boolean ignoreRules) {
      if (userId == null) {
         throw new IllegalArgumentException("parameter refId can not be null");
      }
      if (realmId == null) {
         throw new IllegalArgumentException("parameter realmId can not be null");
      }

      Datastore ds = morphiaDataStore.getDataStore(realmId);
      if (Log.isDebugEnabled()) {
         Log.debug("DataStore: dataBaseName:" + ds.getDatabase().getName() + " retrieved via realmId:" + realmId);
      }

      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("userId", userId));
      // Add filters based upon rule and resourceContext;
      Filter[] qfilters = new Filter[1];
      if (!ignoreRules) {
         qfilters = getFilterArray(filters, getPersistentClass());
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

   /***
    * by default save to the configured system realm not the users realm.
    * @param value
    * @return
    */
   @Override
   public CredentialUserIdPassword save (CredentialUserIdPassword value) {
      return this.save(securityUtils.getSystemRealm(), value);
   }

   @Override
   public CloseableIterator<CredentialUserIdPassword> getStreamByQuery (int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
      return  super.getStreamByQuery(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()), skip, limit, query, sortFields, projectionFields);
   }

   @Override
   public long delete (CredentialUserIdPassword obj) throws ReferentialIntegrityViolationException {
      return  super.delete(securityUtils.getSystemRealm(), obj);
   }

   @Override
   public long delete (@org.jetbrains.annotations.NotNull(value = "ObjectId is required to be non null") ObjectId id) throws ReferentialIntegrityViolationException {
      return delete(securityUtils.getSystemRealm(), id);
   }

   @Override
   public long updateActiveStatus (ObjectId id, boolean active) {
      return super.updateActiveStatus(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()),id, active);
   }

   @Override
   public long update (@org.jetbrains.annotations.NotNull ObjectId id, @org.jetbrains.annotations.NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
      return super.update(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()), id, pairs);
   }

   @Override
   public CredentialUserIdPassword merge (@org.jetbrains.annotations.NotNull CredentialUserIdPassword entity) {
      return super.merge(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()), entity);
   }

   /**
    * by default save to the configured system realm not the users realm.
    * @param entities
    * @return
    */
   @Override
   public List<CredentialUserIdPassword> save(List<CredentialUserIdPassword> entities) {
      return this.save(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()),entities);
   }

   @Override
   public Optional<CredentialUserIdPassword> findById (@org.jetbrains.annotations.NotNull ObjectId id) {
      return super.findById(id, securityUtils.getSystemRealm());
   }

   @Override
   public Optional<CredentialUserIdPassword> findByRefName (@org.jetbrains.annotations.NotNull String refName) {
      return super.findByRefName(refName, securityUtils.getSystemRealm());
   }

   @Override
   public List<CredentialUserIdPassword> getAllList () {
      return this.getAllList(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()));
   }

   @Override
   public List<CredentialUserIdPassword> getListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
      return getListByQuery(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()), skip, limit, query, sortFields, projectionFields);
   }
   @Override
   public List<CredentialUserIdPassword> getListFromReferences(List<EntityReference> references) {
      return getListFromReferences(morphiaDataStore.getDataStore(securityUtils.getSystemRealm()), references);
   }
}
