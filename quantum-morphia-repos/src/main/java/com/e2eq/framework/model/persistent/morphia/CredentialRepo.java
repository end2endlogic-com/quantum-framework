package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.base.CloseableIterator;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.util.EnvConfigUtils;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CredentialRepo extends MorphiaRepo<CredentialUserIdPassword> {
   @Inject
   EnvConfigUtils envConfigUtils;

   @Inject
   RealmRepo realmRepo;

   @Override
   public String getDatabaseName () {
      //return morphiaDataStore.getDataStore(securityUtils.getSystemRealm()).getDatabase().getName();
      return envConfigUtils.getSystemRealm();
   }

   /**
    * Returns the list of realm IDs (database names) that match the current user's realmRegEx.
    * The current user is resolved from SecurityContext's PrincipalContext. The set of candidate
    * realms is obtained from the realm catalog stored in the system realm.
    *
    * Behavior:
    * - If the user cannot be resolved or no credential is found, returns an empty list.
    * - If realmRegEx is null/blank, returns an empty list.
    * - If realmRegEx is "*", matches all realms.
    */
   public List<String> getMatchingRealmsForCurrentUser() {
      var pctxOpt = com.e2eq.framework.model.securityrules.SecurityContext.getPrincipalContext();
      if (pctxOpt.isEmpty()) {
         return java.util.Collections.emptyList();
      }
      var pctx = pctxOpt.get();
      String userId = pctx.getUserId();
      String defaultRealm = pctx.getDefaultRealm();

      Optional<CredentialUserIdPassword> ocred = findByUserId(userId, defaultRealm, true);
      if (ocred.isEmpty()) {
         return java.util.Collections.emptyList();
      }

      String realmRegex = ocred.get().getRealmRegEx();
      if (realmRegex == null || realmRegex.isBlank()) {
         return java.util.Collections.emptyList();
      }

      String patternText = "*".equals(realmRegex) ? ".*" : realmRegex;
      java.util.regex.Pattern pattern;
      try {
         pattern = java.util.regex.Pattern.compile(patternText);
      } catch (Exception e) {
         // Fallback to exact match if invalid regex provided
         pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(realmRegex));
      }

      List<Realm> realms = realmRepo.getAllList(envConfigUtils.getSystemRealm());
      List<String> matches = new java.util.ArrayList<>(realms.size());
      for (var r : realms) {
         String realmId = r.getDatabaseName();
         if (realmId != null && pattern.matcher(realmId).matches()) {
            matches.add(realmId);
         }
      }
      return matches;
   }

   public Optional<CredentialUserIdPassword> findBySubject(@NotNull String subject) {
      return findBySubject(subject, envConfigUtils.getSystemRealm(), false);
   }

   public Optional<CredentialUserIdPassword> findBySubject(@NotNull String subject, @NotNull String realmId) {
      return findBySubject(subject, realmId, false);
   }

   public Optional<CredentialUserIdPassword> findBySubject(@NotNull String subject, @NotNull String realmId, boolean ignoreRules) {
      Datastore ds = morphiaDataStore.getDataStore(realmId);
      if (Log.isDebugEnabled()) {
         Log.debug("DataStore: dataBaseName:" + ds.getDatabase().getName() + " retrieved via realmId:" + realmId);
      }

      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("subject", subject));
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
      return Optional.ofNullable(obj);
   }


   public Optional<CredentialUserIdPassword> findByUserId(@NotNull String userId)
   {
      return findByUserId( userId, envConfigUtils.getSystemRealm());
   }

   public Optional<CredentialUserIdPassword> findByUserId( @NotNull String userId,  @NotNull String realmId) {
      return findByUserId( userId, realmId,false);
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
      return this.save(envConfigUtils.getSystemRealm(), value);
   }

   @Override
   public CloseableIterator<CredentialUserIdPassword> getStreamByQuery (int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
      return  super.getStreamByQuery(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()), skip, limit, query, sortFields, projectionFields);
   }

   @Override
   public long delete (CredentialUserIdPassword obj) throws ReferentialIntegrityViolationException {
      return  super.delete(envConfigUtils.getSystemRealm(), obj);
   }

   @Override
   public long delete (@org.jetbrains.annotations.NotNull(value = "ObjectId is required to be non null") ObjectId id) throws ReferentialIntegrityViolationException {
      return delete(envConfigUtils.getSystemRealm(), id);
   }

   @Override
   public long updateActiveStatus (ObjectId id, boolean active) {
      return super.updateActiveStatus(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()),id, active);
   }

   @Override
   public long update (@org.jetbrains.annotations.NotNull ObjectId id, @org.jetbrains.annotations.NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
      return super.update(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()), id, pairs);
   }

   @Override
   public CredentialUserIdPassword merge (@org.jetbrains.annotations.NotNull CredentialUserIdPassword entity) {
      return super.merge(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()), entity);
   }

   /**
    * by default save to the configured system realm not the users realm.
    * @param entities
    * @return
    */
   @Override
   public List<CredentialUserIdPassword> save(List<CredentialUserIdPassword> entities) {
      return this.save(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()),entities);
   }

   @Override
   public Optional<CredentialUserIdPassword> findById (@org.jetbrains.annotations.NotNull ObjectId id) {
      return super.findById(id, envConfigUtils.getSystemRealm());
   }

   @Override
   public Optional<CredentialUserIdPassword> findByRefName (@org.jetbrains.annotations.NotNull String refName) {
      return super.findByRefName(refName, envConfigUtils.getSystemRealm());
   }

   @Override
   public List<CredentialUserIdPassword> getAllList () {
      return this.getAllList(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()));
   }

   @Override
   public List<CredentialUserIdPassword> getListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
      return getListByQuery(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()), skip, limit, query, sortFields, projectionFields);
   }
   @Override
   public List<CredentialUserIdPassword> getListFromReferences(List<EntityReference> references) {
      return getListFromReferences(morphiaDataStore.getDataStore(envConfigUtils.getSystemRealm()), references);
   }
}
