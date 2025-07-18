package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.annotations.StateGraph;
import com.e2eq.framework.annotations.Stateful;
import com.e2eq.framework.annotations.TrackReferences;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.StateNode;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.model.persistent.security.FunctionalDomain;
import com.e2eq.framework.model.securityrules.*;
import com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchema;
import com.google.common.reflect.TypeToken;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.Datastore;
import dev.morphia.annotations.Reference;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import dev.morphia.query.*;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperator;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.qute.i18n.MessageTemplateLocator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.PathParam;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static dev.morphia.query.Sort.ascending;
import static dev.morphia.query.Sort.descending;

/**
 * This is a base class that implements base functionality using Morphia as
 * the library to store classes in Mongodb
 *
 * @param <T> The model class this repo will use.
 */
public  abstract class MorphiaRepo<T extends UnversionedBaseModel> implements BaseMorphiaRepo<T> {

    @Inject
    protected MorphiaDataStore morphiaDataStore;

    @Inject
    protected RuleContext ruleContext;

    @Inject
    protected SecurityIdentity securityIdentity;

   @ConfigProperty(name = "quantum.realmConfig.defaultRealm"  )
   protected String defaultRealm;

   private TypeToken<T> paramClazz = new TypeToken<>(getClass()) {};

    @Inject
    protected MessageTemplateLocator messageTemplateLocator;

    @Inject
    StateGraphManager stateGraphManager;

    public String getSecurityContextRealmId() {
        String realmId = defaultRealm;

        if (SecurityContext.getPrincipalContext().isPresent() && SecurityContext.getResourceContext().isPresent()) {
            realmId = ruleContext.getRealmId(SecurityContext.getPrincipalContext().get(),
                    SecurityContext.getResourceContext().get());
        }

        if (realmId == null) {
            throw new RuntimeException("Logic error realmId should not be null");
        }

        return realmId;
    }

    @Override
    public String getDatabaseName () {
       return morphiaDataStore.getDataStore(getSecurityContextRealmId()).getDatabase().getName();
    }

    public Filter[] getFilterArray(@NotNull List<Filter> filters, Class<? extends UnversionedBaseModel> modelClass) {
       if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), modelClass);
            if (Log.isDebugEnabled()) {
                Log.debugf("getFilterArray for %s security context: %s", SecurityContext.getPrincipalContext().get().getUserId(), filters);
            }
        } else {
            throw new RuntimeException("Logic error SecurityContext should be present; this implies that an attempt to call a method was made where the user was not logged in");
        }

        Filter[] qfilters = new Filter[filters.size()];
        return filters.toArray(qfilters);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<T> getPersistentClass() {
        return (Class<T>) paramClazz.getRawType();
    }

    @Override
    public void ensureIndexes (String realmId, String collection) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      Objects.requireNonNull(collection, "Collection cannot be null");
      morphiaDataStore.getDataStore(realmId).ensureIndexes(getPersistentClass());
   }


      protected List<String> getDefaultUIActionsFromFD(@NotNull String fdRefName) {
        return getDefaultUIActionsFromFD(morphiaDataStore.getDataStore(getSecurityContextRealmId()), fdRefName);
    }

    protected List<String> getDefaultUIActionsFromFD(Datastore datastore, @NotNull String fdRefName) {
        Filter f = MorphiaUtils.convertToFilter("refName:" + fdRefName, getPersistentClass());
        Query<FunctionalDomain> q = datastore.find(FunctionalDomain.class).filter(f);
        FunctionalDomain fd = q.first();
        List<String> actions;

        if (fd != null) {
            actions = new ArrayList<>(fd.getFunctionalActions().size());
            fd.getFunctionalActions().forEach(fa -> {
                actions.add(fa.getRefName());
            });
        } else {
            actions = Collections.emptyList();
        }

        return actions;
    }




    @Override
    public Optional<T> findById(@NotNull String id) {
        ObjectId oid = new ObjectId(id);
        return findById(oid);
    }
   @Override
   public Optional<T> findById(@NotNull String id, @NotNull String realmId) {
      ObjectId oid = new ObjectId(id);
      return findById(oid, realmId);
   }


    @Override
    public Optional<T> findById(@NotNull ObjectId id) {
        return findById(morphiaDataStore.getDataStore(getSecurityContextRealmId()), id);
    }

   @Override
   public Optional<T> findById (@NotNull ObjectId id, String realmId) {
      return findById(morphiaDataStore.getDataStore(realmId), id);
   }

    @Override
    public Optional<T> findById(@NotNull Datastore datastore, @NotNull ObjectId id) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("_id", id));
        // Add filters based upon rule and resourceContext;
        Filter[] qfilters = getFilterArray(filters, getPersistentClass());

        Query<T> query = datastore.find(getPersistentClass()).filter(qfilters);
        T obj = query.first();

        if (obj != null) {
            List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
            if (!actions.isEmpty()) {
                obj.setDefaultUIActions(actions);
            }
            obj.setRealm(datastore.getDatabase().getName());
        }
        return Optional.ofNullable(obj);
    }

    @Override
    public Optional<T> findByRefName(@NotNull String refName) {
        return findByRefName(morphiaDataStore.getDataStore(getSecurityContextRealmId()), refName);
    }


   @Override
   public Optional<T> findByRefName (@NotNull String refName, String realmId) {
      return findByRefName(morphiaDataStore.getDataStore(realmId), refName);
   }


    @Override
    public Optional<T> findByRefName(@NotNull Datastore datastore, @NotNull String refName) {
        Objects.requireNonNull(refName, "the refName can not be null");

        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("refName", refName));
        // Add filters based upon rule and resourceContext;
        Filter[] qfilters = getFilterArray(filters, getPersistentClass());

        Query<T> query = datastore.find(getPersistentClass()).filter(qfilters);
        T obj = query.first();

        if (obj != null) {
            List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
            if (!actions.isEmpty()) {
                obj.setDefaultUIActions(actions);
            }
            obj.setRealm(datastore.getDatabase().getName());
        }

        return Optional.ofNullable(obj);
    }



    @Override
    public JsonSchema getSchema() {
       throw new NotImplementedException("Not implemented");
    }

    @Override
    public List<T> getAllList() {
        return this.getAllList(morphiaDataStore.getDataStore(getSecurityContextRealmId()));
    }

    @Override
    public List<T> getAllList (String realmId) {
      return this.getAllList(morphiaDataStore.getDataStore(realmId));
   }

    @Override
    public List<T> getAllList(Datastore datastore) {
        return this.getList(datastore,0, 0,  null, null);
    }

    @Override
    public List<T> getListByQuery(int skip, int limit, @Nullable String query) {
        return this.getListByQuery(skip, limit, query, null, null);
    }


    @Override
    public List<EntityReference> getEntityReferenceListByQuery(int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields) {
        return this.getEntityReferenceListByQuery(morphiaDataStore.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields);
    }

    @Override
    public List<EntityReference> getEntityReferenceListByQuery(String realmId, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields) {
        return this.getEntityReferenceListByQuery(morphiaDataStore.getDataStore(realmId),skip, limit, query, sortFields);
    }

    @Override
    public List<EntityReference> getEntityReferenceListByQuery(Datastore datastore, int skip, int limit, @Nullable String query, List<SortField> sortFields) {
        if (skip < 0 ) {
            throw new IllegalArgumentException("skip and or limit can not be negative");
        }

        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), getPersistentClass());
        } else {
            Log.info("Context not set?");
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        MorphiaCursor<T> cursor;
        List<ProjectionField> projectionFields = new ArrayList<>();
        projectionFields.add(new ProjectionField( "refName", ProjectionField.ProjectionType.INCLUDE));
        projectionFields.add(new ProjectionField( "id", ProjectionField.ProjectionType.INCLUDE));
        projectionFields.add(new ProjectionField( "displayName", ProjectionField.ProjectionType.INCLUDE));

        FindOptions findOptions = buildFindOptions(skip, limit, sortFields, projectionFields);

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query, getPersistentClass());
                filters.add(Filters.and(filter));
            }
            Filter[] filterArray = new Filter[filters.size()];
            cursor = datastore.find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(findOptions);
        } else {
            Filter[] filterArray = new Filter[filters.size()];
            cursor = datastore.find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(findOptions);
        }

        List<EntityReference> list = new ArrayList<>();
        List<String> actions = null;
        boolean gotActions = false;
        String realmId = datastore.getDatabase().getName();
        try (cursor) {
            EntityReference entityReference;
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
                model.setRealm(realmId);
                entityReference = model.createEntityReference();
                list.add(entityReference);
            }
        }

        return list;
    }
    @Override
    public List<T> getListFromReferences(List<EntityReference> references) {
        return getListFromReferences(morphiaDataStore.getDataStore(getSecurityContextRealmId()), references);
    }

    @Override
    public List<T> getListFromReferences(String realmId, List<EntityReference> references) {
        return getListFromReferences(morphiaDataStore.getDataStore(realmId), references);
    }
    @Override
    public List<T> getListFromReferences(Datastore datastore, List<EntityReference> references) {
        // Create a list of refNames
        List<String> refNames = references.stream().map(EntityReference::getEntityRefName).collect(Collectors.toList());
        return getListFromRefNames(datastore, refNames);
    }

    protected List<Sort> convertToSort(@NotNull List<SortField> sortFields) {
        List<Sort> sorts = new ArrayList<>();
        if (!sortFields.isEmpty()) {
            for (SortField sortField : sortFields) {
                if (sortField.getSortDirection().equals(SortField.SortDirection.DESC)) {
                    sorts.add(descending(sortField.getFieldName()));
                } else {
                    sorts.add(ascending(sortField.getFieldName()));
                }
            }
        }
        return sorts;
    }

    protected FindOptions convertToProjection(FindOptions options, @NotNull List<ProjectionField> projectionFields) {
        List<String> includedFields = new ArrayList<>();
        List<String> excludedFields = new ArrayList<>();
        for (ProjectionField projectionField : projectionFields) {
            if (projectionField.getProjectionType().equals(ProjectionField.ProjectionType.INCLUDE)) {
                includedFields.add(projectionField.getFieldName());
            } else {
                excludedFields.add(projectionField.getFieldName());
            }
        }
        if (!includedFields.isEmpty()) {
            options.projection().include(includedFields.toArray(new String[includedFields.size()]));
        }
        if (!excludedFields.isEmpty()) {
            options.projection().exclude(excludedFields.toArray(new String[excludedFields.size()]));
        }

        return options;
    }

    protected FindOptions buildFindOptions(int skip, int limit, List<SortField> sortFields, List<ProjectionField> projectionFields) {
        FindOptions findOptions = new FindOptions();

        if (skip > 0) {
            findOptions = findOptions.skip(skip);
        }

        if (limit > 0) {
            findOptions = findOptions.limit(limit);
        }

        if (sortFields != null && !sortFields.isEmpty()) {
            List<Sort> sorts = convertToSort(sortFields);
            findOptions = findOptions.sort(sorts.toArray(new Sort[sorts.size()]));
        }

        if (projectionFields != null && !projectionFields.isEmpty()) {
            findOptions = findOptions.projection().knownFields();
            findOptions = convertToProjection(findOptions, projectionFields);
        }

        return findOptions;
    }

    @Override
    public CloseableIterator<T> getStreamByQuery(Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
        if (skip < 0) {
            throw new IllegalArgumentException("skip cannot be negative");
        }

        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), getPersistentClass());
        } else {
            Log.info("Context not set?");
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        FindOptions findOptions = buildFindOptions(skip, limit, sortFields, projectionFields);

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query, getPersistentClass());
                filters.add(Filters.and(filter));
            }
        }

        Filter[] filterArray = new Filter[filters.size()];
        MorphiaCursor<T> cursor = datastore.find(getPersistentClass())
                .filter(filters.toArray(filterArray))
                .iterator(findOptions);

        return new CloseableIterator<T>() {
            private static final int BATCH_SIZE = 1000; // Adjust this value as needed
            private List<String> actions = null;
            private final List<T> batch = new ArrayList<>(BATCH_SIZE);
            private int currentIndex = 0;

            @Override
            public void close() {
                cursor.close();
            }

            @Override
            public boolean hasNext() {
                if (currentIndex < batch.size()) {
                    return true;
                }
                return fetchNextBatch();
            }

            @Override
            public T next() {
                if (currentIndex >= batch.size() && !fetchNextBatch()) {
                    return null;
                }
                T model = batch.get(currentIndex++);
                processModel(model);
                return model;
            }

            private boolean fetchNextBatch() {
                batch.clear();
                currentIndex = 0;
                for (int i = 0; i < BATCH_SIZE && cursor.hasNext(); i++) {
                    batch.add(cursor.next());
                }
                return !batch.isEmpty();
            }

            private void processModel(T model) {
                if (model != null) {
                    if (actions == null) {
                        actions = getDefaultUIActionsFromFD(model.bmFunctionalDomain());
                    }
                    if (!actions.isEmpty()) {
                        model.setDefaultUIActions(actions);
                    }

                    UIActionList uiActions = model.calculateStateBasedUIActions();
                    model.setActionList(uiActions);
                }
            }
        };
    }

    @Override
    public List<T> getListByQuery(@NotNull Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {

        if (skip < 0 ) {
            throw new IllegalArgumentException("skip and or limit can not be negative");
        }

        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), getPersistentClass());
        } else {
            Log.info("Context not set?");
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        MorphiaCursor<T> cursor;

        FindOptions findOptions = buildFindOptions(skip, limit, sortFields, projectionFields);

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query, getPersistentClass());
                filters.add(Filters.and(filter));
            }
            Filter[] filterArray = new Filter[filters.size()];
            cursor = datastore.find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(findOptions);
        } else {
            Filter[] filterArray = new Filter[filters.size()];
            cursor = datastore.find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(findOptions);
        }

        List<T> list = new ArrayList<>();
        List<String> actions = null;
        boolean gotActions = false;
       String realm = datastore.getDatabase().getName();
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
                model.setRealm(realm);
                list.add(model);
            }
        }

        return list;
    }

    // Convenience method that uses the default datastore
    @Override
    public CloseableIterator<T> getStreamByQuery(int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
        return getStreamByQuery(morphiaDataStore.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields, projectionFields);
    }

   @Override
   public List<T> getListByQuery (String realmId, int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
      return getListByQuery(morphiaDataStore.getDataStore(realmId), skip, limit, query, sortFields, projectionFields);
   }

    @Override
    public List<T> getListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
        return getListByQuery(morphiaDataStore.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields, projectionFields);
    }

    @Override
    public List<T> getList(int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields) {
        return getList(morphiaDataStore.getDataStore(getSecurityContextRealmId()), skip, limit, filters, sortFields);
    }

   @Override
   public List<T> getList (String realmId, int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields) {
      return getList(morphiaDataStore.getDataStore(realmId), skip, limit, filters, sortFields);
   }

    @Override
    public List<T> getList(Datastore datastore, int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields) {

        if (skip < 0 ) {
            throw new IllegalArgumentException("skip can not be negative");
        }

        if (filters == null) {
            filters = new ArrayList<>();
        }

        FindOptions findOptions = buildFindOptions(skip, limit, sortFields, null);

        // Add filters based upon rule and resourceContext;
        Filter[] filterArray = getFilterArray(filters, getPersistentClass());

        MorphiaCursor<T> cursor;

        cursor = datastore.find(getPersistentClass())
                .filter(filterArray)
                .iterator(findOptions);

        List<T> list = new ArrayList<>();
        List<String> actions = null;
        boolean gotActions = false;
       String realm = datastore.getDatabase().getName();
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

                model.setRealm(realm);
                list.add(model);
            }
        }

        return list;
    }



   @Override
   public List<T> getListFromIds ( @NotNull(value = "List of objectids can not be null") List<ObjectId> ids) {
      return getListFromIds(getSecurityContextRealmId(),ids);
   }

   @Override
    public List<T> getListFromIds(@NotNull(value = "RealmId can not be null") String realmId,@NotNull(value="List of objectids can not be null") @NotEmpty (message = "list of ids can not be empty") List<ObjectId> ids) {
        return getListFromIds(morphiaDataStore.getDataStore(realmId), ids);
    }

    @Override
    public List<T> getListFromIds(Datastore datastore, @NotNull(value="List of objectids can not be null") @NotEmpty(message = "list of ids can not be empty") List<ObjectId> ids) {
        // get a list using an in clause based upon the ids passed in
        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), getPersistentClass());
        } else {
            Log.info("Context not set?");
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        filters.add(Filters.in("_id", ids));

        FindOptions findOptions = new FindOptions();

        Filter[] filterArray = new Filter[filters.size()];
        Query<T> query = morphiaDataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass())
                .filter(filters.toArray(filterArray));

        List<T> list = query.iterator(findOptions).toList();

        List<String> actions = null;
        boolean gotActions = false;
       String realm = datastore.getDatabase().getName();
        for (T model : list) {
            if (!gotActions) {
                actions = this.getDefaultUIActionsFromFD(model.bmFunctionalDomain());
                gotActions = true;
            }

            if (!actions.isEmpty()) {
                model.setDefaultUIActions(actions);
            }

            UIActionList uiActions = model.calculateStateBasedUIActions();
            model.setActionList(uiActions);
            model.setRealm(realm);
        }

        return list;
    }


   @Override
   public List<T> getListFromRefNames ( List<String> refNames) {
      return getListFromRefNames(getSecurityContextRealmId(), refNames);
   }

   @Override
    public List<T> getListFromRefNames(String realmId,List<String> refNames) {
        return getListFromRefNames(morphiaDataStore.getDataStore(realmId), refNames);
    }

    @Override
    public List<T> getListFromRefNames(Datastore datastore, List<String> refNames) {
        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get(), getPersistentClass());
        } else {
            Log.info("Context not set?");
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        filters.add(Filters.in("refName", refNames));

        FindOptions findOptions = new FindOptions();
       String realm= getSecurityContextRealmId();

        Filter[] filterArray = new Filter[filters.size()];
        Query<T> query = morphiaDataStore.getDataStore(realm).find(getPersistentClass())
                .filter(filters.toArray(filterArray));

        List<T> list = query.iterator(findOptions).toList();

        List<String> actions = null;
        boolean gotActions = false;

        for (T model : list) {
            if (!gotActions) {
                actions = this.getDefaultUIActionsFromFD(model.bmFunctionalDomain());
                gotActions = true;
            }

            if (!actions.isEmpty()) {
                model.setDefaultUIActions(actions);
            }

            UIActionList uiActions = model.calculateStateBasedUIActions();
            model.setActionList(uiActions);
            model.setRealm(realm);
        }

        return list;
    }


   @Override
   public long getCount ( @Nullable String filter) {
      return getCount(getSecurityContextRealmId(), filter);
   }

   @Override
    public long getCount(@NotNull(value="realmId can not be null") String realmId, @Nullable String query) {
        return getCount(morphiaDataStore.getDataStore(realmId), query);
    }


    @Override
    public long getCount(Datastore datastore, @Nullable String query) {
        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters,
                    SecurityContext.getPrincipalContext().get(),
                    SecurityContext.getResourceContext().get(),
                    getPersistentClass());
        } else {
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query, getPersistentClass());
                filters.add(Filters.and(filter));
            }
        }
        Filter[] filterArray = new Filter[filters.size()];
        long count = datastore.find(getPersistentClass())
                .filter(filters.toArray(filterArray))
                .count();
        return count;
    }

    // Update / Write based api/s
    // TODO consider breaking this apart into to seperate classes and injecting them separately so you can
    // have different implementations for read vs. write.

    public MorphiaSession startSession(String realm) {
        return morphiaDataStore.getDataStore(realm).startSession();
    }

    protected void setDefaultValues(T model) {
        if (model.getId() == null) {
            model.setId(new ObjectId());
        }

        if (model.getRefName() == null || model.getRefName().trim().isEmpty()) {
            model.setRefName (model.getId().toString());
        }

        if (model.getDisplayName() == null) {
            model.setDisplayName(model.getRefName());
        }
    }

   protected void validateStateTransitions(Datastore datastore, @Valid T value) throws InvalidStateTransitionException, IllegalAccessException {
      // If the entity has an ID, it's an update; fetch the existing entity
      if (!value.isSkipValidation() && value.getId() != null ){
         Optional<T> existingEntityOpt = findById(datastore, value.getId());
         if (existingEntityOpt.isPresent()) {
            T existingEntity = existingEntityOpt.get();
            validateStateFields(value, existingEntity);
         } else {
            throw new IllegalStateException("Entity with ID " + value.getId() + " not found for update");
         }
      } else if (!value.isSkipValidation()) {
         // For new entities, validate that initial states are valid
         validateInitialStates(value);
      }
   }

   private void validateStateFields(T newEntity, T existingEntity) throws IllegalAccessException, InvalidStateTransitionException {
      if (!existingEntity.isSkipValidation() &&
             existingEntity.getClass().getAnnotation(Stateful.class) !=null)
         for (Field field : getAllFields(newEntity.getClass())) {
            StateGraph stateGraph = field.getAnnotation(StateGraph.class);
            if (stateGraph != null) {
               field.setAccessible(true);
               String newState = (String) field.get(newEntity);
               String currentState = (String) field.get(existingEntity);
               if (newState != null) {
                  stateGraphManager.validateTransition(
                     stateGraph.graphName(),
                     currentState != null ? currentState : "",
                     newState
                  );
               }
            }
         }
   }

   private void validateInitialStates(T entity) throws IllegalAccessException, InvalidStateTransitionException {
       // check if the entity type is annotated with Stateful annotation
       if (entity.getClass().getAnnotation(Stateful.class) !=null)
          for (Field field : getAllFields(entity.getClass())) {
            StateGraph stateGraph = field.getAnnotation(StateGraph.class);
            if (stateGraph != null) {
               field.setAccessible(true);
               String newState = (String) field.get(entity);
               if (newState != null) {
                  // Check if the state exists in the graph
                  StringState graph = stateGraphManager.getStateGraphs().get(stateGraph.graphName());
                  if (graph == null) {
                     throw new InvalidStateTransitionException(
                        String.format("State graph %s not configured", stateGraph.graphName()));
                  }
                  if (!graph.getStates().containsKey(newState)) {
                     // create a string of all known initial states
                     String knownInitialStates = graph.getStates().values().stream()
                         .filter(StateNode::isInitialState)
                         .map(StateNode::getState)
                         .collect(Collectors.joining(", "));
                     throw new InvalidStateTransitionException(
                        String.format("Invalid initial state:%s for graph %s. Known initial states:%s",  newState, stateGraph.graphName(), knownInitialStates));
                  }
               }
            }
         }
   }

   private List<Field> getAllFields(Class<?> clazz) {
      List<Field> fields = new ArrayList<>();
      while (clazz != null && clazz != Object.class) {
         fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
         clazz = clazz.getSuperclass();
      }
      return fields;
   }

    public T save(@NotNull MorphiaSession session, @Valid T value) {
        if (value.getClass().getAnnotation(Stateful.class)!= null) {
           try {
              validateStateTransitions(session, value);
           } catch (InvalidStateTransitionException | IllegalAccessException e) {
              throw new RuntimeException("State transition validation failed", e);
           }
        }
       setDefaultValues(value);
        value.validate();
        return session.save(value);
    }

    @Override
    public T save(@Valid T value) {
        return save(getSecurityContextRealmId(), value);
    }


    @Override
    public List<T> save(List<T> entities) {
        return save(morphiaDataStore.getDataStore(getSecurityContextRealmId()),entities);
    }

    @Override
    public List<T> save(@NotNull Datastore datastore, List<T> entities) {
       entities.forEach(entity -> {
          if (entity.getClass().getAnnotation(Stateful.class)!= null) {
             try {
                validateStateTransitions(datastore, entity);
             } catch (InvalidStateTransitionException | IllegalAccessException e) {
                throw new RuntimeException("State transition validation failed for entity: " + entity.getRefName(), e);
             }
          }
          setDefaultValues(entity);
          entity.validate();
       });
       return datastore.save(entities);
    }

    @Override
    public List<T> save(@NotNull MorphiaSession session, List<T> entities) {

       entities.forEach(entity -> {
          if (entity.getClass().getAnnotation(Stateful.class)!= null) {
             try {
                validateStateTransitions(session, entity);
             } catch (InvalidStateTransitionException | IllegalAccessException e) {
                throw new RuntimeException("State transition validation failed for entity: " + entity.getRefName(), e);
             }
          }
          setDefaultValues(entity);
          entity.validate();
       });

       return session.save(entities);
    }


    @Override
    public T save(@NotNull Datastore datastore, @Valid T value) {
       if (value.getClass().getAnnotation(Stateful.class)!= null) {
          try {
             validateStateTransitions(datastore, value);
          } catch (InvalidStateTransitionException | IllegalAccessException e) {
             throw new RuntimeException("State transition validation failed", e);
          }
       }
       setDefaultValues(value);
       value.validate();
       return datastore.save(value);
    }

   @Override
   public T save(@NotNull String realmId, @Valid T value) {
      return save(morphiaDataStore.getDataStore(realmId), value);
   }


    /**
     * Remove references in classes from this object by looking for any referenced BaseModel instances
     * @param obj - the object that may reference other classes
     * @param session - the session we are participating in i.e the transaction
     */
    public void removeReferenceConstraint(T obj, MorphiaSession session) {
        Mapper mapper = morphiaDataStore.getDataStore(getSecurityContextRealmId()).getMapper();
        EntityModel mappedClass = mapper.getEntityModel(obj.getClass());

        for (PropertyModel mappedField : mappedClass.getProperties()) {
            if (mappedField.isReference() && mappedField.hasAnnotation(TrackReferences.class)) {
                if (mappedField.getAccessor().get(obj)!= null && BaseModel.class.isAssignableFrom(mappedField.getAccessor().get(obj).getClass())) {
                    BaseModel baseModel = (mappedField.getAccessor().get(obj) != null) ? (BaseModel) mappedField.getAccessor().get(obj) : null;
                    if (baseModel != null) {
                        if (!baseModel.getReferences().contains(obj)) {
                            baseModel.getReferences().remove(obj);
                            session.save(mappedField.getAccessor().get(obj));
                        }
                    }
                } else
                    // if this is a collection of the class that is the same as us ( obj ) then see if its non null and if its annotated to track references
                if (mappedField.getAccessor().get(obj)!=null &&
                        java.util.Collection.class.isAssignableFrom(mappedField.getAccessor().get(obj).getClass()) &&
                  mappedField.hasAnnotation(TrackReferences.class)) {
                    {
                        // if so we need for each item in the collection we  remove the reference to this class
                        java.util.Collection<BaseModel> baseModels = (java.util.Collection<BaseModel>) mappedField.getAccessor().get(obj);
                        if (baseModels != null) {
                            for (BaseModel baseModel : baseModels) {
                                ReferenceEntry entry = new ReferenceEntry(obj.getId(), obj.getClass().getTypeName(),
                                        obj.getRefName());
                                if (baseModel.getReferences().contains(entry)) {
                                    if (!baseModel.getReferences().remove(entry)) {
                                        Log.warn("Reference entry not found in baseModel: " + entry.toString());
                                    }
                                    session.save(baseModel);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public long delete(T obj) throws ReferentialIntegrityViolationException {
        return delete(getSecurityContextRealmId(), obj);
    }

    @Override
    public long delete(String realmId,T obj) throws ReferentialIntegrityViolationException{
        Objects.requireNonNull(obj, "Null argument passed to delete, api requires a non-null object");
        Objects.requireNonNull(obj.getId(), "Null argument passed to delete, api requires a non-null object id");
      return delete(realmId, obj.getId());
    }

    @Override
    public long delete( @NotNull( value ="ObjectId is required to be non null") ObjectId id) throws ReferentialIntegrityViolationException {
       return delete(getSecurityContextRealmId(), id);
    }

    @Override
    public long delete (@NotNull String realmId, @NotNull ObjectId id) throws ReferentialIntegrityViolationException {
        Objects.requireNonNull(id, "Null argument passed to delete, api requires a non-null object");

        // find the object to delete
        Optional<T> oobj = this.findById(id, realmId);

        if (oobj.isPresent()) {
            // assuming the record exists
            T obj = oobj.get();
            DeleteResult result = null;
            // if there are no references to this object, then we can just delete it
            if (obj.getReferences() == null || obj.getReferences().isEmpty()) {
                // delete the object and remove any references that it may have had to parents
                try (MorphiaSession s = morphiaDataStore.getDataStore(realmId).startSession()) {
                    s.startTransaction();
                    removeReferenceConstraint(obj, s);
                    result = s.delete(obj);
                    s.commitTransaction();
                }
                // we are done so now we can return the number of deleted records which should be just one

            } else {
                // there are references to this object, we need to find out which ones and if they are all stale or not
                Set<ReferenceEntry> entriesToRemove = new HashSet<>();

                // Iterate through references in this class and ensure that there are
                // actually references and that the list of references is not stale

                // for each reference, check if the referenced object still exists
                for (ReferenceEntry reference : obj.getReferences()) {
                    try (MorphiaSession s = morphiaDataStore.getDataStore(realmId).startSession()) {
                        s.startTransaction();
                        // find the referenced object
                        try {
                            ClassLoader classLoader = this.getClass().getClassLoader();
                            Class<?> clazz = classLoader.loadClass(reference.getType());
                            Query<?> q = s.find(clazz).filter(Filters.eq("_id", reference.getReferencedId()));
                            if (q.count() != 0) {
                                entriesToRemove.add(reference);
                            }
                        } catch (ClassNotFoundException e) {
                            Log.warn("Failed to load class: " + reference.getType() + "removing reference");
                            entriesToRemove.add(reference);
                        }
                        // ok we now know all the reference which we found which will be in the entriesToRemove set.
                        if (entriesToRemove.isEmpty()) {
                            // entities are empty so there are no valid references to this object.
                            // no remove all the reference there may from this class to other classes.
                            removeReferenceConstraint(obj, s);
                            // now actually delete the object
                            result = s.delete(obj);

                            // just for completeness we can remove all the entries now
                            obj.getReferences().removeAll(entriesToRemove);

                            // commit the transaction and we are done.
                            s.commitTransaction();
                        } else {
                            // there are references to this object so we can not delete this object until those are removed.
                            // build a useful error message and throw an exception
                            HashSet<String> referencingClasses = new HashSet<>();
                            for (ReferenceEntry rreference : obj.getReferences()) {
                                referencingClasses.add(rreference.getType());
                            }
                            String buffereferencingClassesString = referencingClasses.stream().collect(Collectors.joining(", "));
                            throw new ReferentialIntegrityViolationException("Can not delete object because it has references from other objects to this one that would corrupt the relationship. Referencing classes: " + buffereferencingClassesString);
                        }
                    }
                }
            }
            return result.getDeletedCount();
        }
        Log.warn("Object not found for deletion: " + id);
        return 0;
    }

    @Override
    public long delete(@NotNull(value ="the datastore must not be null" ) Datastore datastore, T aobj) throws ReferentialIntegrityViolationException{
        long rc=0;
          try (MorphiaSession s = datastore.startSession()) {
              s.startTransaction();
              try {
                  rc=delete(s, aobj);
                  s.commitTransaction();
              } catch ( ReferentialIntegrityViolationException e) {
                  s.abortTransaction();
                  throw e;
              }
            return rc;
        }
    }

    @Override
    public long delete(@NotNull MorphiaSession session, T aobj) throws ReferentialIntegrityViolationException{
        Objects.requireNonNull(aobj, "Null argument passed to delete, api requires a non-null object");
        Objects.requireNonNull(aobj.getId(), "Null argument passed to delete, api requires a non-null id");
        Optional<T> oobj = this.findById(session,aobj.getId());
        if (!oobj.isPresent()) {
            return 0;
        }
        T obj = oobj.get();
        DeleteResult result;
        if (obj.getReferences() == null || obj.getReferences().isEmpty()) {
                removeReferenceConstraint(obj, session);
                result = session.delete(obj);
                return result.getDeletedCount();
        } else {
            Set<ReferenceEntry> entriesToRemove = new HashSet<>();
            // Iterate through references in this class and ensure that there are
            // actually references and that the list of references is not stale
            for (ReferenceEntry reference : obj.getReferences()) {
                try {
                    ClassLoader classLoader = this.getClass().getClassLoader();
                    Class<?> clazz = classLoader.loadClass(reference.getType());
                    Query<?> q = session.find(clazz).filter(Filters.eq("_id", reference.getReferencedId()));
                    if (q.count() == 0) {
                        entriesToRemove.add(reference);
                    }
                } catch (ClassNotFoundException e) {
                    Log.warn("Failed to load class: " + reference.getType() + "removing reference");
                    entriesToRemove.add(reference);
                }
            }
            obj.getReferences().removeAll(entriesToRemove);

            // There may still be valid reference left if so complain
            if (obj.getReferences().isEmpty()) {
                removeReferenceConstraint(obj, session);
                result = session.delete(obj);
                return result.getDeletedCount();
            } else {
                HashSet<String> referencingClasses = new HashSet<>();
                for (ReferenceEntry reference : obj.getReferences()) {
                    referencingClasses.add(reference.getType());
                }
                String buffereferencingClassesString = referencingClasses.stream().collect(Collectors.joining(", "));
                throw new ReferentialIntegrityViolationException("Can not delete object because it has references from other objects to this one that would corrupt the relationship. Referencing classes: " + buffereferencingClassesString);
            }
        }
    }

    @Override
    public long updateActiveStatus (@PathParam("id") ObjectId id, boolean active) {
       return updateActiveStatus(morphiaDataStore.getDataStore(getSecurityContextRealmId()),id, active);
   }

   @Override
   public long updateActiveStatus (Datastore datastore, @PathParam("id") ObjectId id, boolean active) {
      UpdateOperator updateOp = UpdateOperators.set("active", active );
      UpdateResult update;
      update = datastore.find(getPersistentClass()).filter(Filters.eq("_id", id))
                     .update(updateOp);

     return update.getModifiedCount();
   }


    @Override
    @SafeVarargs
    public final long update(@NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        ObjectId oid = new ObjectId(id);
        return update(oid, pairs);
    }

   @Override
   public long update (String realmId, @NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
      return update(morphiaDataStore.getDataStore(realmId), id, pairs);
   }

   @Override
   public long update ( @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
       return update(morphiaDataStore.getDataStore(getSecurityContextRealmId()), id, pairs);
   }


    @Override
    public long update (String realmId, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return update(morphiaDataStore.getDataStore(realmId), id, pairs);
    }

    private Field getFieldFromHierarchy(Class<?> clazz, String fieldName)  throws NoSuchFieldException {
        Class<?> currentClass = clazz;
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // Field not found in current class, move to the superclass
                currentClass = currentClass.getSuperclass();
            }
        }
        // If we've exhausted the hierarchy without finding the field, throw an exception
        throw new NoSuchFieldException("Field '" + fieldName + "' not found in class hierarchy of " + clazz.getName());
    }

    @SafeVarargs
    @Override
    public final long update(MorphiaSession session, @NotNull String id, @NotNull Pair<String, Object>... pairs) {
        List<UpdateOperator> updateOperators = new ArrayList<>();
        for (Pair<String, Object> pair : pairs) {
            // check that the pair key corresponds to a field in the persistent class that is an enum
            Field field = null;
            try {
                field = getFieldFromHierarchy(getPersistentClass(),pair.getKey());
                Reference ref = field.getAnnotation(Reference.class);
                if (ref!= null) {
                    //TODO fix this case where there is an update to a reference field
                    Log.warn("Update to class that contains references");
                    throw new NotSupportedException("Field:" + field + " is a managed reference, and not updatable via put.  Use Post");
                }

                if (field.getType().isEnum()) {
                    // check that the pair value is a valid enum value of te field
                    if (!Arrays.stream(field.getType().getEnumConstants()).anyMatch(e -> e.toString().equals(pair.getValue().toString()))) {
                        throw new IllegalArgumentException("Invalid value for enum field " + pair.getKey() + " can't set value:" + pair.getValue());
                    }

                }
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
            updateOperators.add(UpdateOperators.set(pair.getKey(), pair.getValue()));
        }
        if (updateOperators.isEmpty()) {
            return 0;
        }

        UpdateResult update;
        if (updateOperators.size() == 1) {
            update = session.find(getPersistentClass()).filter(Filters.eq("_id", id))
                    .update(updateOperators.get(0));
        } else {
            UpdateOperator[] ops = updateOperators.toArray(new UpdateOperator[0]);
            update = session.find(getPersistentClass()).filter(Filters.eq("_id", id))
                    .update(ops[0], Arrays.copyOfRange(ops, 1, ops.length));
        }

        return update.getModifiedCount();
    }

    @Override
    public long update(Datastore datastore, @NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        ObjectId oid = new ObjectId(id);
        return update(datastore, oid, pairs);
    }

    @Override
    public long update(Datastore datastore, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
       List<UpdateOperator> updateOperators = new ArrayList<>();
       List<String> reservedFields = List.of("refName", "id", "version", "references", "auditInfo", "persistentEvents");

       // Fetch the current entity to validate state transitions
       Optional<T> currentEntityOpt = findById(datastore, id);
       if (!currentEntityOpt.isPresent()) {
          return 0;
       }
       T currentEntity = currentEntityOpt.get();

       for (Pair<String, Object> pair : pairs) {
          if (reservedFields.contains(pair.getKey())) {
             throw new IllegalArgumentException("Field:" + pair.getKey() + " is a reserved field and can't be updated");
          }

          Field field;
          try {
             field = getFieldFromHierarchy(getPersistentClass(), pair.getKey());

             // Check for StateGraph annotation
             StateGraph stateGraph = field.getAnnotation(StateGraph.class);
             if (stateGraph != null && pair.getValue() instanceof String) {
                field.setAccessible(true);
                String currentState = (String) field.get(currentEntity);
                stateGraphManager.validateTransition(
                   stateGraph.graphName(),
                   currentState != null ? currentState : "",
                   (String) pair.getValue()
                );
             }

             // Existing validation checks
             Reference ref = field.getAnnotation(Reference.class);
             if (ref != null) {
                throw new NotSupportedException("Field:" + field + " is a managed reference, and not updatable via put. Use Post");
             }

             if (field.getType().isEnum()) {
                if (!Arrays.stream(field.getType().getEnumConstants())
                        .anyMatch(e -> e.toString().equals(pair.getValue().toString()))) {
                   throw new IllegalArgumentException(
                      "Invalid value for enum field " + pair.getKey() + " can't set value:" + pair.getValue());
                }
             }

             if (field.getAnnotation(NotNull.class) != null && pair.getValue() == null) {
                throw new IllegalArgumentException(
                   "Field " + pair.getKey() + " is not nullable, but null value provided");
             }

             if (!field.getType().isAssignableFrom(pair.getValue().getClass())) {
                throw new IllegalArgumentException(
                   "Invalid value for field " + pair.getKey() +
                      " can't set value:" + pair.getValue() +
                      " expected type: " + field.getType().toString() +
                      " but got: " + pair.getValue().getClass().getSimpleName());
             }

             updateOperators.add(UpdateOperators.set(pair.getKey(), pair.getValue()));
          } catch (NoSuchFieldException | IllegalAccessException e) {
             throw new RuntimeException(e);
          }
       }

       if (updateOperators.isEmpty()) {
          throw new IllegalArgumentException("No update pairs provided, or a parsing of the update pairs failed");
       }

       if (BaseModel.class.isAssignableFrom(getPersistentClass())) {
          updateOperators.add(UpdateOperators.inc("version", 1));
       }

       updateOperators.add(UpdateOperators.set("auditInfo.lastUpdateTs", new Date()));
       updateOperators.add(UpdateOperators.set("auditInfo.lastUpdateIdentity", securityIdentity.getPrincipal().getName()));

       UpdateResult update;
       if (updateOperators.size() == 1) {
          update = datastore.find(getPersistentClass()).filter(Filters.eq("_id", id))
                      .update(updateOperators.get(0));
       } else {
          UpdateOperator[] ops = updateOperators.toArray(new UpdateOperator[0]);
          update = datastore.find(getPersistentClass()).filter(Filters.eq("_id", id))
                      .update(ops[0], Arrays.copyOfRange(ops, 1, ops.length));
       }

       return update.getModifiedCount();
    }



   @Override
    public long update(MorphiaSession session, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) {
        List<UpdateOperator> updateOperators = new ArrayList<>();
        for (Pair<String, Object> pair : pairs) {
            // check that the pair key corresponds to a field in the persistent class that is an enum
            Field field = null;
            try {
                field = getFieldFromHierarchy(getPersistentClass(),pair.getKey());
                Reference ref = field.getAnnotation(Reference.class);
                if (ref!= null) {
                    throw new NotSupportedException("Field:" + field + " is a managed reference, and not updatable via put.  Use Post");
                }

                if (field.getType().isEnum()) {
                    // check that the pair value is a valid enum value of te field
                    if (!Arrays.stream(field.getType().getEnumConstants()).anyMatch(e -> e.toString().equals(pair.getValue().toString()))) {
                        throw new IllegalArgumentException("Invalid value for enum field " + pair.getKey() + " can't set value:" + pair.getValue());
                    }

                }
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
            updateOperators.add(UpdateOperators.set(pair.getKey(), pair.getValue()));
        }
        if (updateOperators.isEmpty()) {
            return 0;
        }

        UpdateResult update;
        if (updateOperators.size() == 1) {
            update = session.find(getPersistentClass()).filter(Filters.eq("_id", id))
                    .update(updateOperators.get(0));
        } else {
            UpdateOperator[] ops = updateOperators.toArray(new UpdateOperator[0]);
            update = session.find(getPersistentClass()).filter(Filters.eq("_id", id))
                    .update(ops[0], Arrays.copyOfRange(ops, 1, ops.length));
        }

        return update.getModifiedCount();
    }

    @Override
    public T merge(@NotNull T entity){
        return merge(morphiaDataStore.getDataStore(getSecurityContextRealmId()), entity);
    }

    @Override
    public T merge(Datastore datastore, @NotNull T entity) {
       if (entity.getClass().getAnnotation(Stateful.class) != null) {
          try {
             validateStateTransitions(datastore, entity);
          } catch (InvalidStateTransitionException | IllegalAccessException e) {
             throw new RuntimeException("State transition validation failed", e);
          }
       }
        return datastore.merge(entity);
    }

    @Override
    public T merge(MorphiaSession session, @NotNull T entity) {
       if (entity.getClass().getAnnotation(Stateful.class) != null) {
          try {
             validateStateTransitions(session, entity);
          } catch (InvalidStateTransitionException | IllegalAccessException e) {
             throw new RuntimeException("State transition validation failed", e);
          }
       }
        return session.merge(entity);
    }

    @Override
    public List<T> merge(List<T> entities) {
        return merge(morphiaDataStore.getDataStore(getSecurityContextRealmId()), entities);
    }

    @Override
    public List<T> merge(Datastore datastore, List<T> entities) {
        return datastore.merge(entities);
    }

    @Override
    public List<T> merge(MorphiaSession session, List<T> entities) {
       entities.forEach(entity -> {
          if (entity.getClass().getAnnotation(Stateful.class) != null) {
             try {
                validateStateTransitions(session, entity);
             } catch (InvalidStateTransitionException | IllegalAccessException e) {
                throw new RuntimeException("State transition validation failed for entity: " + entity.getRefName(), e);
             }
          }
       });
        return session.merge(entities);
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

            alist.forEach((UIAction action) -> {

                String actionString = action.getLabel().toUpperCase().replace(" ", "_");

                ResourceContext rcontext = new ResourceContext.Builder()
                        .withFunctionalDomain(model.bmFunctionalDomain())
                        .withArea(model.bmFunctionalArea())
                        .withAction(actionString)
                        .withResourceId(model.getRefName())
                        //.withRealm(securityUtils.getSystemRealm())
                        .build();
                PrincipalContext pcontext;
                if (SecurityContext.getPrincipalContext().isPresent()) {
                    pcontext = SecurityContext.getPrincipalContext().get();
                } else {
                    throw new IllegalStateException("Principal Context should be non null");
                }

                SecurityCheckResponse sr = ruleContext.checkRules(pcontext, rcontext);
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


}
