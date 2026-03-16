package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.annotations.StateGraph;
import com.e2eq.framework.annotations.Stateful;
import com.e2eq.framework.annotations.TrackReferences;
import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.StateNode;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.security.runtime.RuleContext;
import com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchema;
import com.google.common.reflect.TypeToken;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.Datastore;
import dev.morphia.MorphiaDatastore;
import dev.morphia.UpdateOptions;
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
import jakarta.enterprise.inject.Instance;

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
    protected Instance<PostPersistHook> postPersistHooks;

    @Inject
    protected Instance<PreDeleteHook> preDeleteHooks;

    @Inject
    protected Instance<PostDeleteHook> postDeleteHooks;

    @ConfigProperty(name = "ontology.auto-materialize", defaultValue = "true")
    protected boolean autoMaterialize;

    private void callPostPersistHooks(String realmId, Object entity) {
        lifecycleHooks().callPostPersistHooks(realmId, entity);
    }

    private void callPreDeleteHooks(String realmId, Object entity) {
        lifecycleHooks().callPreDeleteHooks(realmId, entity);
    }

    private void callPostDeleteHooks(String realmId, Class<?> entityClass, String idAsString) {
        lifecycleHooks().callPostDeleteHooks(realmId, entityClass, idAsString);
    }

    /**
     * Check if a field has the OntologyProperty annotation without requiring
     * the ontology module as a compile-time dependency.
     */
    private static boolean hasOntologyPropertyAnnotation(java.lang.reflect.Field field) {
        for (java.lang.annotation.Annotation ann : field.getAnnotations()) {
            if (ann.annotationType().getName().equals("com.e2eq.ontology.annotations.OntologyProperty")) {
                return true;
            }
        }
        return false;
    }

    @Inject
    protected MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    protected RuleContext ruleContext;

    @Inject
    protected SecurityIdentity securityIdentity;

    @Inject
    protected CredentialRepo credentialRepo;

    @Inject
    protected com.e2eq.framework.util.EnvConfigUtils envConfigUtils;

    @Inject
    protected com.e2eq.framework.util.SecurityUtils securityUtils;

   @ConfigProperty(name = "quantum.realmConfig.defaultRealm"  )
   protected String defaultRealm;

   private TypeToken<T> paramClazz = new TypeToken<>(getClass()) {};

    @Inject
    protected MessageTemplateLocator messageTemplateLocator;

    @Inject
    protected StateGraphManager stateGraphManager;

    private void ensureSecurityContextFromIdentity() {
        securityContextResolver().ensureSecurityContextFromIdentity();
    }

    public String getSecurityContextRealmId() {
        return securityContextResolver().getSecurityContextRealmId();
    }

    @Override
    public MorphiaDataStoreWrapper getMorphiaDataStoreWrapper () {
       return morphiaDataStoreWrapper;
    }

    @Override
    public MorphiaDatastore getMorphiaDataStore() {
       return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
    }

    @Override
    public String getDatabaseName () {
       return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).getDatabase().getName();
    }

    public Filter[] getFilterArray(@NotNull List<Filter> filters, Class<? extends UnversionedBaseModel> modelClass) {
        return securityFilterBuilder().getFilterArray(filters, modelClass);
    }

    private RepoLifecycleHooks lifecycleHooks() {
        return new RepoLifecycleHooks(autoMaterialize, postPersistHooks, preDeleteHooks, postDeleteHooks);
    }

    private RepoSecurityContextResolver securityContextResolver() {
        return new RepoSecurityContextResolver(securityIdentity, credentialRepo, envConfigUtils, ruleContext, defaultRealm);
    }

    private RepoSecurityFilterBuilder securityFilterBuilder() {
        return new RepoSecurityFilterBuilder(securityContextResolver(), ruleContext);
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
      morphiaDataStoreWrapper.getDataStore(realmId).ensureIndexes(getPersistentClass());
   }


      protected List<String> getDefaultUIActionsFromFD(@NotNull String fdRefName) {
        return getDefaultUIActionsFromFD(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), fdRefName);
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
   public Optional<T> findById (@NotNull String id, boolean ignoreRules) {
       ObjectId oid = new ObjectId(id);
      return this.findById(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), oid, ignoreRules);
   }

   @Override
   public Optional<T> findById(@NotNull String id, @NotNull String realmId) {
      ObjectId oid = new ObjectId(id);
      return findById(oid, realmId);
   }


    @Override
    public Optional<T> findById(@NotNull ObjectId id) {
        return findById(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), id);
    }

   @Override
   public Optional<T> findById (@NotNull ObjectId id, String realmId) {
      return findById(morphiaDataStoreWrapper.getDataStore(realmId), id, false);
   }

   @Override
   public Optional<T> findById (@NotNull ObjectId id, String realmId, boolean ignoreRules) {
       return findById(morphiaDataStoreWrapper.getDataStore(realmId), id, ignoreRules);
   }

   @Override
   public Optional<T> findById(@NotNull Datastore datastore, @NotNull ObjectId id) {
      return findById(datastore, id, false);
   }

    @Override
    public Optional<T> findById(@NotNull Datastore datastore, @NotNull ObjectId id, boolean ignoreRules) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("_id", id));
        // Add filters based upon rule and resourceContext;
       Filter[] qfilters = new Filter[1];
       if (!ignoreRules) {
          qfilters = getFilterArray(filters, getPersistentClass());
       } else {
          qfilters = filters.toArray(qfilters);
       }

        Query<T> query = datastore.find(getPersistentClass()).filter(qfilters);
        T obj = query.first();

        if (obj != null) {
            List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
            if (!actions.isEmpty()) {
                obj.setDefaultUIActions(actions);
            }
            obj.setModelSourceRealm(datastore.getDatabase().getName());
        }
        return Optional.ofNullable(obj);
    }

    @Override
    public Optional<T> findByRefName(@NotNull String refName) {
        return findByRefName(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), refName);
    }


   @Override
   public Optional<T> findByRefName (@NotNull String refName, String realmId) {
      return findByRefName(morphiaDataStoreWrapper.getDataStore(realmId), refName);
   }

   @Override
   public Optional<T> findByRefName(@NotNull Datastore datastore, @NotNull String refName) {
      return findByRefName(datastore, refName, false);
   }


    @Override
    public Optional<T> findByRefName(@NotNull Datastore datastore, @NotNull String refName, boolean ignoreRules) {
        Objects.requireNonNull(refName, "the refName can not be null");

        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("refName", refName));
        // Add filters based upon rule and resourceContext;
       Filter[] qfilters = new Filter[1];;
       if (!ignoreRules) {
          qfilters = getFilterArray(filters, getPersistentClass());
       } else {
          qfilters = filters.toArray(qfilters);
       }

        Query<T> query = datastore.find(getPersistentClass()).filter(qfilters);
        T obj = query.first();

        if (obj != null) {
            List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
            if (!actions.isEmpty()) {
                obj.setDefaultUIActions(actions);
            }
            obj.setModelSourceRealm(datastore.getDatabase().getName());
        }

        return Optional.ofNullable(obj);
    }



    @Override
    public JsonSchema getSchema() {
       throw new NotImplementedException("Not implemented");
    }

    @Override
    public List<T> getAllList() {
        return this.getAllList(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()));
    }

    @Override
    public List<T> getAllList (String realmId) {
      return this.getAllList(morphiaDataStoreWrapper.getDataStore(realmId));
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
        return this.getEntityReferenceListByQuery(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields);
    }

    @Override
    public List<EntityReference> getEntityReferenceListByQuery(String realmId, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields) {
        return this.getEntityReferenceListByQuery(morphiaDataStoreWrapper.getDataStore(realmId),skip, limit, query, sortFields);
    }

    @Override
    public List<EntityReference> getEntityReferenceListByQuery(Datastore datastore, int skip, int limit, @Nullable String query, List<SortField> sortFields) {
        if (skip < 0 ) {
            throw new IllegalArgumentException("skip and or limit can not be negative");
        }

        List<Filter> filters = new ArrayList<>();
        filters = securityFilterBuilder().buildSecuredFilters(filters, getPersistentClass());

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
                model.setModelSourceRealm(realmId);
                entityReference = model.createEntityReference();
                list.add(entityReference);
            }
        }

        return list;
    }
    @Override
    public List<T> getListFromReferences(List<EntityReference> references) {
        return getListFromReferences(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), references);
    }

    @Override
    public List<T> getListFromReferences(String realmId, List<EntityReference> references) {
        return getListFromReferences(morphiaDataStoreWrapper.getDataStore(realmId), references);
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
            findOptions.skip(skip);
        }

        if (limit > 0) {
            findOptions.limit(limit);
        }

        if (sortFields != null && !sortFields.isEmpty()) {
            List<Sort> sorts = convertToSort(sortFields);
            findOptions.sort(sorts.toArray(new Sort[sorts.size()]));
        }

        if (projectionFields != null && !projectionFields.isEmpty()) {
            findOptions.projection().knownFields();
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
        filters = securityFilterBuilder().buildSecuredFilters(filters, getPersistentClass());

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
        filters = securityFilterBuilder().buildSecuredFilters(filters, getPersistentClass());


        MorphiaCursor<T> cursor;

        FindOptions findOptions = buildFindOptions(skip, limit, sortFields, projectionFields);

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query, getPersistentClass());
                filters.add(Filters.and(filter));
                Log.debugf("Running with filters:%s", filters.stream().map(Filter::toString).collect(Collectors.joining(",")));

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
                model.setModelSourceRealm(realm);
                list.add(model);
            }
        }

        return list;
    }

    // Convenience method that uses the default datastore
    @Override
    public CloseableIterator<T> getStreamByQuery(int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
        return getStreamByQuery(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields, projectionFields);
    }

   @Override
   public List<T> getListByQuery (String realmId, int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
      return getListByQuery(morphiaDataStoreWrapper.getDataStore(realmId), skip, limit, query, sortFields, projectionFields);
   }

    @Override
    public List<T> getListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
        return getListByQuery(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields, projectionFields);
    }

    @Override
    public List<T> getList(int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields) {
        return getList(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), skip, limit, filters, sortFields);
    }

   @Override
   public List<T> getList (String realmId, int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields) {
      return getList(morphiaDataStoreWrapper.getDataStore(realmId), skip, limit, filters, sortFields);
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

                model.setModelSourceRealm(realm);
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
        return getListFromIds(morphiaDataStoreWrapper.getDataStore(realmId), ids);
    }

    @Override
    public List<T> getListFromIds(Datastore datastore, @NotNull(value="List of objectids can not be null") @NotEmpty(message = "list of ids can not be empty") List<ObjectId> ids) {
        // get a list using an in clause based upon the ids passed in
        List<Filter> filters = new ArrayList<>();
        filters = securityFilterBuilder().buildSecuredFilters(filters, getPersistentClass());

        filters.add(Filters.in("_id", ids));

        FindOptions findOptions = new FindOptions();

        Filter[] filterArray = new Filter[filters.size()];
        Query<T> query = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).find(getPersistentClass())
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
            model.setModelSourceRealm(realm);
        }

        return list;
    }


   @Override
   public List<T> getListFromRefNames ( List<String> refNames) {
      return getListFromRefNames(getSecurityContextRealmId(), refNames);
   }

   @Override
    public List<T> getListFromRefNames(String realmId,List<String> refNames) {
        return getListFromRefNames(morphiaDataStoreWrapper.getDataStore(realmId), refNames);
    }

    @Override
    public List<T> getListFromRefNames(Datastore datastore, List<String> refNames) {
        List<Filter> filters = new ArrayList<>();
        filters = securityFilterBuilder().buildSecuredFilters(filters, getPersistentClass());

        filters.add(Filters.in("refName", refNames));

        FindOptions findOptions = new FindOptions();
       String realm= getSecurityContextRealmId();

        Filter[] filterArray = new Filter[filters.size()];
        Query<T> query = morphiaDataStoreWrapper.getDataStore(realm).find(getPersistentClass())
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
            model.setModelSourceRealm(realm);
        }

        return list;
    }


   @Override
   public long getCount ( @Nullable String filter) {
      return getCount(getSecurityContextRealmId(), filter);
   }

   @Override
    public long getCount(@NotNull(value="realmId can not be null") String realmId, @Nullable String query) {
        return getCount(morphiaDataStoreWrapper.getDataStore(realmId), query);
    }


    @Override
    public long getCount(Datastore datastore, @Nullable String query) {
        List<Filter> filters = new ArrayList<>();
        filters = securityFilterBuilder().buildSecuredFilters(filters, getPersistentClass());

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
        return morphiaDataStoreWrapper.getDataStore(realm).startSession();
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
        T saved = session.save(value);
        callPostPersistHooks(getSecurityContextRealmId(), saved);
        return saved;
    }

    @Override
    public T save(@Valid T value) {
        return save(getSecurityContextRealmId(), value);
    }


    @Override
    public List<T> save(List<T> entities) {
        return save(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()),entities);
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
       List<T> saved = datastore.save(entities);
       // invoke hooks for each saved entity
       String realmId = getSecurityContextRealmId();
       for (T e : saved) callPostPersistHooks(realmId, e);
       return saved;
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

       List<T> saved = session.save(entities);
       String realmId = getSecurityContextRealmId();
       for (T e : saved) callPostPersistHooks(realmId, e);
       return saved;
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
       T saved = datastore.save(value);
       callPostPersistHooks(getSecurityContextRealmId(), saved);
       return saved;
    }

   @Override
   public T save(@NotNull String realmId, @Valid T value) {
      return save(morphiaDataStoreWrapper.getDataStore(realmId), value);
   }


    /**
     * Remove references in classes from this object by looking for any referenced BaseModel instances
     * @param obj - the object that may reference other classes
     * @param session - the session we are participating in i.e the transaction
     */
    public void removeReferenceConstraint(T obj, MorphiaSession session) {
        Mapper mapper = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()).getMapper();
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
       Objects.requireNonNull(obj, "Null argument passed to delete, api requires a non-null object");
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
       Objects.requireNonNull(id, "Null ID argument passed to delete, api requires a non-null id");
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
                try (MorphiaSession s = morphiaDataStoreWrapper.getDataStore(realmId).startSession()) {
                    s.startTransaction();
                    // ontology pre-delete hooks (may throw to block)
                    try { callPreDeleteHooks(realmId, obj); } catch (RuntimeException ex) { s.abortTransaction(); throw ex; }
                    removeReferenceConstraint(obj, s);
                    result = s.delete(obj);
                    // ontology post-delete hooks
                    try { callPostDeleteHooks(realmId, obj.getClass(), String.valueOf(obj.getRefName()!=null?obj.getRefName():obj.getId())); } catch (Throwable ignored) {}
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
                    try (MorphiaSession s = morphiaDataStoreWrapper.getDataStore(realmId).startSession()) {
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
                            // ontology pre-delete hooks (may throw to block)
                            try { callPreDeleteHooks(realmId, obj); } catch (RuntimeException ex) { s.abortTransaction(); throw ex; }
                            // now actually delete the object
                            result = s.delete(obj);

                            // just for completeness we can remove all the entries now
                            obj.getReferences().removeAll(entriesToRemove);

                            // ontology post-delete hooks
                            try { callPostDeleteHooks(realmId, obj.getClass(), String.valueOf(obj.getRefName()!=null?obj.getRefName():obj.getId())); } catch (Throwable ignored) {}
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
    public long updateActiveStatus (@PathParam("id") ObjectId id, ActiveStatus activeStatus) {
       return updateActiveStatus(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), id, activeStatus);
   }

   @Override
   public long updateActiveStatus (Datastore datastore, @PathParam("id") ObjectId id, ActiveStatus activeStatus) {
      UpdateOperator updateOp = UpdateOperators.set("activeStatus", activeStatus);
      UpdateResult update;
      update = datastore.find(getPersistentClass()).filter(Filters.eq("_id", id))
                     .update(updateOp);

     return update.getMatchedCount();
   }

   @Override
   public long updateActiveStatus(@NotNull String id, ActiveStatus activeStatus) {
      return updateActiveStatus(new ObjectId(id), activeStatus);
   }

   @Override
   public long updateActiveStatus(@NotNull String realmId, @NotNull String id, ActiveStatus activeStatus) {
      return updateActiveStatus(morphiaDataStoreWrapper.getDataStore(realmId), new ObjectId(id), activeStatus);
   }

    @Override
    public long update(@NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        ObjectId oid = new ObjectId(id);
        return update(oid, pairs);
    }

   @Override
   @SafeVarargs
   public final long update (String realmId, @NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
      return update(morphiaDataStoreWrapper.getDataStore(realmId), id, pairs);
   }

   @Override
   public long update (@NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
       return update(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), id, pairs);
   }


    @Override
    @SafeVarargs
    public final long update (String realmId, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return update(morphiaDataStoreWrapper.getDataStore(realmId), id, pairs);
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
                if (ref != null) {
                    Log.warn("Update to class that contains references");
                    throw new NotSupportedException("Field:" + field + " is a managed reference, and not updatable via put. Use Post");
                }
                if (hasOntologyPropertyAnnotation(field)) {
                    Log.warn("Update to class that contains ontology properties");
                    throw new NotSupportedException("Field:" + field + " is an ontology property, and not updatable via put. Use save() to update relationships");
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
    @SafeVarargs
    public final long update(Datastore datastore, @NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        ObjectId oid = new ObjectId(id);
        return update(datastore, oid, pairs);
    }

    @Override
    @SafeVarargs
    public final long update(Datastore datastore, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
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
             if (hasOntologyPropertyAnnotation(field)) {
                throw new NotSupportedException("Field:" + field + " is an ontology property, and not updatable via put. Use save() to update relationships");
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
   @SafeVarargs
    public final long update(MorphiaSession session, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) {
        List<UpdateOperator> updateOperators = new ArrayList<>();
        for (Pair<String, Object> pair : pairs) {
            // check that the pair key corresponds to a field in the persistent class that is an enum
            Field field = null;
            try {
                field = getFieldFromHierarchy(getPersistentClass(),pair.getKey());
                Reference ref = field.getAnnotation(Reference.class);
                if (ref != null) {
                    throw new NotSupportedException("Field:" + field + " is a managed reference, and not updatable via put. Use Post");
                }
                if (hasOntologyPropertyAnnotation(field)) {
                    throw new NotSupportedException("Field:" + field + " is an ontology property, and not updatable via put. Use save() to update relationships");
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

    // --- Bulk update implementations ---
    @Override
    @SafeVarargs
    public final long updateManyByQuery(@Nullable String query, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByQuery(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), query, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByQuery(@NotNull String realmId, @Nullable String query, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByQuery(morphiaDataStoreWrapper.getDataStore(realmId), query, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByQuery(@NotNull Datastore datastore, @Nullable String query, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByQuery(datastore, query, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByQuery(@NotNull Datastore datastore, @Nullable String query, boolean ignoreRules, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        List<Filter> filters = new ArrayList<>();
        if (query != null && !query.trim().isEmpty()) {
            Filter qf = MorphiaUtils.convertToFilter(query, getPersistentClass());
            filters.add(Filters.and(qf));
        }
        Filter[] qfilters;
        if (!ignoreRules) {
            qfilters = getFilterArray(filters, getPersistentClass());
        } else {
            qfilters = filters.toArray(new Filter[filters.size()]);
        }
        List<UpdateOperator> ops = buildValidatedUpdateOperators(pairs);
        if (BaseModel.class.isAssignableFrom(getPersistentClass())) {
            ops.add(UpdateOperators.inc("version", 1));
        }
        ops.add(UpdateOperators.set("auditInfo.lastUpdateTs", new Date()));
        ops.add(UpdateOperators.set("auditInfo.lastUpdateIdentity", securityIdentity.getPrincipal().getName()));

        UpdateOperator[] arr = ops.toArray(new UpdateOperator[0]);
        UpdateResult res = datastore.find(getPersistentClass()).filter(qfilters)
                .update(new UpdateOptions().multi(true), arr[0], Arrays.copyOfRange(arr, 1, arr.length));
        return res.getModifiedCount();
    }

    @Override
    @SafeVarargs
    public final long updateManyByIds(@NotNull List<ObjectId> ids, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByIds(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), ids, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByIds(@NotNull String realmId, @NotNull List<ObjectId> ids, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByIds(morphiaDataStoreWrapper.getDataStore(realmId), ids, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByIds(@NotNull Datastore datastore, @NotNull List<ObjectId> ids, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByIds(datastore, ids, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByIds(@NotNull Datastore datastore, @NotNull List<ObjectId> ids, boolean ignoreRules, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        Objects.requireNonNull(ids, "ids must not be null");
        if (ids.isEmpty()) return 0;
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.in("_id", ids));
        Filter[] qfilters;
        if (!ignoreRules) {
            qfilters = getFilterArray(filters, getPersistentClass());
        } else {
            qfilters = filters.toArray(new Filter[filters.size()]);
        }
        List<UpdateOperator> ops = buildValidatedUpdateOperators(pairs);
        if (BaseModel.class.isAssignableFrom(getPersistentClass())) {
            ops.add(UpdateOperators.inc("version", 1));
        }
        ops.add(UpdateOperators.set("auditInfo.lastUpdateTs", new Date()));
        ops.add(UpdateOperators.set("auditInfo.lastUpdateIdentity", securityIdentity.getPrincipal().getName()));

        UpdateOperator[] arr = ops.toArray(new UpdateOperator[0]);
        UpdateResult res = datastore.find(getPersistentClass()).filter(qfilters)
                .update(new UpdateOptions().multi(true), arr[0], Arrays.copyOfRange(arr, 1, arr.length));
        return res.getModifiedCount();
    }

    @Override
    @SafeVarargs
    public final long updateManyByRefAndDomain(@NotNull List<Pair<String, com.e2eq.framework.model.persistent.base.DataDomain>> items,
                                         @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByRefAndDomain(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), items, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByRefAndDomain(@NotNull String realmId,
                                         @NotNull List<Pair<String, com.e2eq.framework.model.persistent.base.DataDomain>> items,
                                         @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByRefAndDomain(morphiaDataStoreWrapper.getDataStore(realmId), items, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByRefAndDomain(@NotNull Datastore datastore,
                                         @NotNull List<Pair<String, com.e2eq.framework.model.persistent.base.DataDomain>> items,
                                         @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        return updateManyByRefAndDomain(datastore, items, false, pairs);
    }

    @Override
    @SafeVarargs
    public final long updateManyByRefAndDomain(@NotNull Datastore datastore,
                                         @NotNull List<Pair<String, com.e2eq.framework.model.persistent.base.DataDomain>> items,
                                         boolean ignoreRules,
                                         @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) return 0;
        List<Filter> orClauses = new ArrayList<>();
        for (Pair<String, com.e2eq.framework.model.persistent.base.DataDomain> it : items) {
            String ref = it.getLeft();
            com.e2eq.framework.model.persistent.base.DataDomain dd = it.getRight();
            List<Filter> ands = new ArrayList<>();
            ands.add(Filters.eq("refName", ref));
            // match full embedded dataDomain object
            ands.add(Filters.eq("dataDomain", dd));
            orClauses.add(Filters.and(ands.toArray(new Filter[0])));
        }
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.or(orClauses.toArray(new Filter[0])));
        Filter[] qfilters;
        if (!ignoreRules) {
            qfilters = getFilterArray(filters, getPersistentClass());
        } else {
            qfilters = filters.toArray(new Filter[filters.size()]);
        }
        List<UpdateOperator> ops = buildValidatedUpdateOperators(pairs);
        if (BaseModel.class.isAssignableFrom(getPersistentClass())) {
            ops.add(UpdateOperators.inc("version", 1));
        }
        ops.add(UpdateOperators.set("auditInfo.lastUpdateTs", new Date()));
        ops.add(UpdateOperators.set("auditInfo.lastUpdateIdentity", securityIdentity.getPrincipal().getName()));

        UpdateOperator[] arr = ops.toArray(new UpdateOperator[0]);
        UpdateResult res = datastore.find(getPersistentClass()).filter(qfilters)
                .update(new UpdateOptions().multi(true), arr[0], Arrays.copyOfRange(arr, 1, arr.length));
        return res.getModifiedCount();
    }

   @SafeVarargs
    private  List<UpdateOperator> buildValidatedUpdateOperators(@NotNull Pair<String, Object>... pairs) {
        Objects.requireNonNull(pairs, "update pairs must not be null");
        List<UpdateOperator> updateOperators = new ArrayList<>();
        List<String> reservedFields = List.of("refName", "id", "version", "references", "auditInfo", "persistentEvents");
        for (Pair<String, Object> pair : pairs) {
            if (reservedFields.contains(pair.getKey())) {
                throw new IllegalArgumentException("Field:" + pair.getKey() + " is a reserved field and can't be updated");
            }
            Field field;
            try {
                field = getFieldFromHierarchy(getPersistentClass(), pair.getKey());
                Reference ref = field.getAnnotation(Reference.class);
                if (ref != null) {
                    throw new NotSupportedException("Field:" + field + " is a managed reference, and not updatable via put. Use Post");
                }
                if (hasOntologyPropertyAnnotation(field)) {
                    throw new NotSupportedException("Field:" + field + " is an ontology property, and not updatable via put. Use save() to update relationships");
                }
                if (field.getType().isEnum()) {
                    if (!Arrays.stream(field.getType().getEnumConstants())
                            .anyMatch(e -> e.toString().equals(String.valueOf(pair.getValue())))) {
                        throw new IllegalArgumentException("Invalid value for enum field " + pair.getKey() + " can't set value:" + pair.getValue());
                    }
                }
                if (field.getAnnotation(NotNull.class) != null && pair.getValue() == null) {
                    throw new IllegalArgumentException("Field " + pair.getKey() + " is not nullable, but null value provided");
                }
                if (pair.getValue() != null && !field.getType().isAssignableFrom(pair.getValue().getClass())) {
                    throw new IllegalArgumentException("Invalid value for field " + pair.getKey() +
                            " can't set value:" + pair.getValue() +
                            " expected type: " + field.getType() +
                            " but got: " + pair.getValue().getClass().getSimpleName());
                }
                updateOperators.add(UpdateOperators.set(pair.getKey(), pair.getValue()));
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }
        if (updateOperators.isEmpty()) {
            throw new IllegalArgumentException("No update pairs provided, or a parsing of the update pairs failed");
        }
        return updateOperators;
    }

    @Override
    public T merge(@NotNull T entity){
        return merge(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), entity);
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
        return merge(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), entities);
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
        if (model.getDataDomain() == null) {
            // When dataDomain is missing (common with projections or legacy data),
            // skip UI action calculation to avoid NPE and set empty actions.
            Log.warn("Skipping UI actions: dataDomain is null for " + model.getClass().getSimpleName() +
                    " refName=" + model.getRefName());
            model.setActionList(new UIActionList());
            return model;
        }

        Map<DataDomain, UIActionList> actions = new HashMap<DataDomain, UIActionList>();
        DataDomain dd = model.getDataDomain();

        UIActionList uiactions = actions.get(dd);

        if (uiactions == null) {
            UIActionList mactions = new UIActionList(model.calculateStateBasedUIActions().size());
            UIActionList alist = model.calculateStateBasedUIActions();

            // Resolve functional mapping once per model (cached per class)
            com.e2eq.framework.annotations.support.FunctionalMappingInfo mi =
                    com.e2eq.framework.annotations.support.FunctionalMappingResolver.resolve(
                            model.getClass(), model::bmFunctionalArea, model::bmFunctionalDomain);
            String area = mi.area;
            String domain = mi.domain;

            // Resolve principal context once
            PrincipalContext pcontext = SecurityContext.getPrincipalContext()
                    .orElseThrow(() -> new IllegalStateException("Principal Context should be non null"));

            String resourceId = model.getRefName();

            alist.forEach((UIAction action) -> {
                String actionString = action.getLabel().toUpperCase().replace(" ", "_");

                ResourceContext rcontext = new ResourceContext.Builder()
                        .withFunctionalDomain(domain)
                        .withArea(area)
                        .withAction(actionString)
                        .withResourceId(resourceId)
                        .build();

                SecurityCheckResponse sr = ruleContext.checkRules(pcontext, rcontext);
                if (sr.getFinalEffect().equals(RuleEffect.ALLOW)) {
                    mactions.add(action);
                } else if (Log.isDebugEnabled()) {
                    Log.debug("Action " + action.getLabel() + " is not allowed" + " for principal:" + pcontext.getUserId());
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

        for (T model : collection.getRows()) {
            // First, filter this model's actions based on permissions
            fillUIActions(model);

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

   public Filter securityAnd(Class<? extends UnversionedBaseModel> modelClass, Filter... others) {
      List<Filter> base = new ArrayList<>();
      // compute permission filters for this modelClass
      Filter[] sec = getFilterArray(base, modelClass);
      // combine with any additional functional filters
      Filter[] all = new Filter[sec.length + others.length];
      System.arraycopy(sec, 0, all, 0, sec.length);
      System.arraycopy(others, 0, all, sec.length, others.length);
      return Filters.and(all);
   }


}
