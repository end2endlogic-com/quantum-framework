package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.DynamicSearchRequest;
import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.model.persistent.security.FunctionalDomain;
import com.e2eq.framework.model.securityrules.*;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.google.common.reflect.TypeToken;
import com.mongodb.client.result.DeleteResult;
import dev.morphia.query.FindOptions;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.Query;
import dev.morphia.query.Update;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperator;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;

/**
 This is a base class that implements base functionality using Morphia as
 the library to store classes in Mongodb
 @param <T> The model class this repo will use.
 */
public abstract class MorphiaRepo<T extends BaseModel> implements BaseRepo<T> {
   @Inject
   protected MorphiaDataStore dataStore;

   @Inject
   RuleContext ruleContext;

   TypeToken<T> paramClazz = new TypeToken<>(getClass()) {};

   public String getDefaultRealmId () {
      String realmId = RuleContext.DefaultRealm;

      if (SecurityContext.getPrincipalContext().isPresent() && SecurityContext.getResourceContext().isPresent()) {
         realmId =  ruleContext.getRealmId(SecurityContext.getPrincipalContext().get(),
            SecurityContext.getResourceContext().get());
      }

      if (realmId == null) {
         throw new RuntimeException("Logic error realmId should not be null");
      }

      return realmId;
   }

   public Filter[] getFilterArray(@NotNull List<Filter> filters) {
      if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
         filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get());
      }

      Filter[] qfilters = new Filter[filters.size()];
      return filters.toArray(qfilters);
   }

   @Override
   public Class<T> getPersistentClass() {
        return (Class<T>) paramClazz.getRawType();
   }



   protected List<String> getDefaultUIActionsFromFD(@NotNull String fdRefName){

      Filter f = MorphiaUtils.convertToFilter("refName:" + fdRefName);

      Query<FunctionalDomain> q = dataStore.getDataStore(getDefaultRealmId()).find(FunctionalDomain.class).filter(f);
      FunctionalDomain fd = q.first();

      List<String> actions;

      if (fd!= null)  {
         actions = new ArrayList<>(fd.getFunctionalActions().size());
         fd.getFunctionalActions().forEach( fa -> {
            actions.add(fa.getRefName());
         });
      } else {
         actions = Collections.emptyList();
      }

      return actions;
   }

   @Override
   public Optional<T> findById(@NotNull String id) {
      if (id == null) {
         throw new IllegalArgumentException("parameter id can not be null");
      }
      ObjectId oid = new ObjectId(id);
      return findById(oid);
   }

   @Override
   public Optional<T> findById(@NotNull ObjectId id) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("_id", id));
      // Add filters based upon rule and resourceContext;
      Filter[] qfilters = getFilterArray(filters);

      Query<T> query = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass()).filter(qfilters);
      T obj = query.first();

      if (obj != null ) {
         List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
         if (!actions.isEmpty()) {
            obj.setDefaultUIActions(actions);
         }
      }
      return Optional.ofNullable(obj);
   }


   @Override
   public Optional<T> findByRefName (@NotNull String refName) {
      Objects.requireNonNull(refName, "the refName can not be null");

      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("refName", refName));
      // Add filters based upon rule and resourceContext;
      Filter[] qfilters = getFilterArray(filters);

      Query<T> query = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass()).filter(qfilters);
      T obj = query.first();

      if (obj != null ) {
         List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
         if (!actions.isEmpty()) {
            obj.setDefaultUIActions(actions);
         }
      }

      return Optional.ofNullable(obj);
   }

   @Override
   public JsonSchema getSchema () {
      return null;
   }


   public List<T> getAllList() {
      return this.getList(0, 0, null, null, null);
   }

   @Override
   public List<T> getListByQuery(int skip, int limit, String query) {
      return this.getListByQuery(skip, limit, query, null, null );
   }

   @Override
   public List<T> getListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields, List<String> projectedProperties){

      if (skip < 0 || limit < 0) {
         throw new IllegalArgumentException("skip and or limit can not be negative");
      }

      List<Filter> filters =  new ArrayList<>();

      if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
         filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get());
      } else {
         Log.info("Context not set?");
         throw new RuntimeException("Resource Context is not set in thread, check security configuration");
      }

      MorphiaCursor<T> cursor;

      if (query != null && !query.isEmpty()) {
         String cleanQuery = query.trim();
         if (!cleanQuery.isEmpty()) {
            Filter filter = MorphiaUtils.convertToFilter(query);
            filters.add(Filters.and(filter));
         }

         Filter[] filterArray = new Filter[filters.size()];
         if (limit>0) {
            cursor = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass())
                        .filter(filters.toArray(filterArray))
                        .iterator(new FindOptions().skip(skip).limit(limit));
         } else {
            cursor = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass())
                        .filter(filters.toArray(filterArray))
                        .iterator(new FindOptions().skip(skip));
         }
      } else {
         Filter[] filterArray = new Filter[filters.size()];
         if (limit > 0) {

            cursor = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass())
                        .filter(filters.toArray(filterArray))
                        .iterator(new FindOptions().skip(skip).limit(limit));
         } else {
            cursor = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass())
                        .filter(filters.toArray(filterArray))
                        .iterator(new FindOptions().skip(skip));
         }
      }

      List<T> list = new ArrayList<>();
      List<String> actions = null;
      boolean gotActions = false;
      try (cursor) {
         for (T model : cursor.toList()) {

            if (!gotActions) {
               actions = this.getDefaultUIActionsFromFD(model.bmFunctionalDomain());
               gotActions = true;
            }

            if (!actions.isEmpty()) {
               model.setDefaultUIActions(actions);
            }

            UIActionList uiActions = model.calculateStateBasedUIActions();
            model.setActionList(uiActions);

            list.add(model);
         }
      }


      return list;
   }

   @Override
   public List<T> getList(int skip, int limit, List<String> columns, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields) {

      if (skip < 0 || limit < 0) {
         throw new IllegalArgumentException("skip and or limit can not be negative");
      }

      if (filters == null) {
         filters = new ArrayList<>();
      }

      if (sortFields != null) {
         throw new UnsupportedOperationException("Sorting not supported");
      }

      // Add filters based upon rule and resourceContext;
      if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
         filters = ruleContext.getFilters(filters,
            SecurityContext.getPrincipalContext().get(),
            SecurityContext.getResourceContext().get());
      } else {
         throw new RuntimeException("Resource Context is not set in thread, check security configuration");
      }

      Filter[] filterArray = new Filter[0];

      MorphiaCursor<T> cursor;
      if (limit > 0) {
       cursor =dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass())
             .filter(filters.toArray(filterArray))
             .iterator(new FindOptions().skip(skip).limit(limit));
      } else {

         cursor = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass())
                     .filter(filters.toArray(filterArray))
                     .iterator();
      }

      List<T> list = new ArrayList<>();
      List<String> actions = null;
      boolean gotActions = false;
      try (cursor) {
         for (T model : cursor.toList()) {

            if (!gotActions) {
               actions = this.getDefaultUIActionsFromFD(model.bmFunctionalDomain());
               gotActions = true;
            }

            if (!actions.isEmpty()) {
               model.setDefaultUIActions(actions);
            }

            UIActionList uiActions = model.calculateStateBasedUIActions();
            model.setActionList(uiActions);

            list.add(model);
         }
      }

      return list;
   }

   // Update / Write based api/s
   // TODO consider breaking this apart into to seperate classes and injecting them seperately so you can
   // have different implementations for read vs. write.

   public MorphiaSession startSession(String realm) {
      return dataStore.getDataStore(realm).startSession();
   }

   public T save(MorphiaSession session, T value) {
      return session.save(value);
   }

   @Override
   public T save( T value) {
      //ruleContext.check(SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get());
      return this.save(getDefaultRealmId(), value);
   }

   @Override
   public T save(String realmId, T value) {
      if (realmId == null || realmId.isEmpty()) {
         throw new IllegalArgumentException("Realm can not be empty or null");
      }

      return dataStore.getDataStore(realmId).save(value);
   }

   @Override
   public long delete (T obj) {
       DeleteResult result = dataStore.getDataStore(getDefaultRealmId()).delete(obj);
       return result.getDeletedCount();
   }


   @Override
   @SafeVarargs
   public final long update (@NotNull String id, @NotNull Pair<String, Object>... pairs){
      ObjectId oid = new ObjectId(id);
      return update(oid, pairs);
   }

   public long update (@NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) {
      List<UpdateOperator> updateOperators = new ArrayList<>();
      for (Pair<String, Object> pair : pairs) {
         // check that the pair key corresponds to a field in the persistent class that is an enum
         Field field = null;
         try {
            field = getPersistentClass().getDeclaredField(pair.getKey());
            if (field.getType().isEnum()) {
               // check that the pair value is a valid enum value of te field
               if (!Arrays.stream(field.getType().getEnumConstants()).anyMatch(e -> e.toString().equals(pair.getValue().toString()))) {
                  throw new IllegalArgumentException("Invalid value for enum field " + pair.getKey() + " can't set value:" + pair.getValue());                }

            }
         } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
         }
         updateOperators.add(UpdateOperators.set(pair.getKey(), pair.getValue()));
      }

      Update<T> update = dataStore.getDataStore(getDefaultRealmId()).find(getPersistentClass()).filter(Filters.eq("_id", id))
              .update(updateOperators);

      return update.execute().getModifiedCount();
   }


   public T fillUIActions(@NotNull T model) {
      Objects.requireNonNull(model);
      Objects.requireNonNull(model.getDataDomain());

      Map<DataDomain, UIActionList> actions = new HashMap<DataDomain, UIActionList>();
      DataDomain dd = model.getDataDomain();

      UIActionList uiactions = actions.get(dd);

      if (uiactions == null) {
         UIActionList mactions = new UIActionList(model.calculateStateBasedUIActions().size());
         UIActionList alist = model.calculateStateBasedUIActions();

        alist.forEach ( (UIAction action) -> {

            String actionString = action.getLabel().toUpperCase().replace(" ", "_");

            ResourceContext rcontext = new ResourceContext.Builder()
                                          .withFunctionalDomain(model.bmFunctionalDomain())
                                          .withArea(model.bmFunctionalArea())
                                          .withAction(actionString)
                                          .withResourceId(model.getRefName())
                                          //.withRealm(SecurityUtils.systemRealm)
                                          .build();
            PrincipalContext pcontext;
            if (SecurityContext.getPrincipalContext().isPresent()) {
               pcontext = SecurityContext.getPrincipalContext().get();
            } else {
               throw new IllegalStateException("Principal Context should be non null");
            }

            SecurityCheckResponse sr = ruleContext.check(pcontext, rcontext);
            if (sr.getFinalEffect().equals(RuleEffect.ALLOW)) {
               mactions.add(action);
            } else {
               if (Log.isDebugEnabled()) {
                  Log.debug("Action " + action.getLabel() + " is not allowed" + " for principal:" + pcontext.getUserId());
               }
            }
         });

         actions.put(dd, mactions);
         uiactions = mactions;
      }

      model.setActionList(uiactions);
      return model;
   }


   public Collection<T> fillUIActions(@NotNull Collection<T> collection) {

      Map<DataDomain, UIActionList> actions = new HashMap<>();

      boolean firstIteration = true;

      for (T model : collection.getRows()) {
         DataDomain domain = model.getDataDomain();
         UIActionList modelActions = model.getActionList();

         if (actions.containsKey(domain)) {
            // Intersect with existing UIActionList
            UIActionList existingActions = actions.get(domain);
            existingActions.retainAll(modelActions);
         } else {
            // New DataDomain, so add the actions to the map
            actions.put(domain, new UIActionList(modelActions)); // Assuming UIActionList has a copy constructor
         }
      }

      Optional<UIActionList> firstUIActionList = actions.values().stream().findFirst();

      if (firstUIActionList.isPresent()) {
         UIActionList commonActions = new UIActionList(firstUIActionList.get()); // Assuming UIActionList has a copy constructor

         for (UIActionList actionList : actions.values()) {
            commonActions.retainAll(actionList);
         }

         collection.setActionList(commonActions);
      }



      return collection;
   }

   @Override
   public long getCount(DynamicSearchRequest searchRequest) {
      return 0;
   }
}
