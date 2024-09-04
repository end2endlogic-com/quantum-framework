package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.model.persistent.security.FunctionalDomain;
import com.e2eq.framework.model.securityrules.*;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.google.common.reflect.TypeToken;
import com.mongodb.client.result.DeleteResult;
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
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;

import static dev.morphia.query.Sort.ascending;
import static dev.morphia.query.Sort.descending;

/**
 * This is a base class that implements base functionality using Morphia as
 * the library to store classes in Mongodb
 *
 * @param <T> The model class this repo will use.
 */
public abstract class MorphiaRepo<T extends BaseModel> implements BaseMorphiaRepo<T> {
    @Inject
    protected MorphiaDataStore dataStore;

    @Inject
    RuleContext ruleContext;

    TypeToken<T> paramClazz = new TypeToken<>(getClass()) {
    };

    public String getSecurityContextRealmId() {
        String realmId = RuleContext.DefaultRealm;

        if (SecurityContext.getPrincipalContext().isPresent() && SecurityContext.getResourceContext().isPresent()) {
            realmId = ruleContext.getRealmId(SecurityContext.getPrincipalContext().get(),
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


    protected List<String> getDefaultUIActionsFromFD(@NotNull String fdRefName) {

        Filter f = MorphiaUtils.convertToFilter("refName:" + fdRefName);

        Query<FunctionalDomain> q = dataStore.getDataStore(getSecurityContextRealmId()).find(FunctionalDomain.class).filter(f);
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
        if (id == null) {
            throw new IllegalArgumentException("parameter id can not be null");
        }
        ObjectId oid = new ObjectId(id);
        return findById(oid);
    }
    @Override
    public Optional<T> findById(@NotNull ObjectId id) {
        if (id == null) {
            throw new IllegalArgumentException("parameter id can not be null");
        }
        return findById(dataStore.getDataStore(getSecurityContextRealmId()), id);
    }

    @Override
    public Optional<T> findById(Datastore datastore, @NotNull ObjectId id) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("_id", id));
        // Add filters based upon rule and resourceContext;
        Filter[] qfilters = getFilterArray(filters);

        Query<T> query = datastore.find(getPersistentClass()).filter(qfilters);
        T obj = query.first();

        if (obj != null) {
            List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
            if (!actions.isEmpty()) {
                obj.setDefaultUIActions(actions);
            }
        }
        return Optional.ofNullable(obj);
    }


    @Override
    public Optional<T> findByRefName(@NotNull String refName) {
        Objects.requireNonNull(refName, "the refName can not be null");

        List<Filter> filters = new ArrayList<>();
        filters.add(Filters.eq("refName", refName));
        // Add filters based upon rule and resourceContext;
        Filter[] qfilters = getFilterArray(filters);

        Query<T> query = dataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(qfilters);
        T obj = query.first();

        if (obj != null) {
            List<String> actions = this.getDefaultUIActionsFromFD(obj.bmFunctionalDomain());
            if (!actions.isEmpty()) {
                obj.setDefaultUIActions(actions);
            }
        }

        return Optional.ofNullable(obj);
    }



    @Override
    public JsonSchema getSchema() {
        return null;
    }


    public List<T> getAllList() {
        return this.getList(0, 0, null, null, null);
    }

    @Override
    public List<T> getListByQuery(int skip, int limit, String query) {
        return this.getListByQuery(skip, limit, query, null, null);
    }

    protected List<Sort> convertToSort(List<SortField> sortFields) {
        List<Sort> sorts = new ArrayList<>();
        if (sortFields != null && !sortFields.isEmpty()) {
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

    protected FindOptions convertToProjection(FindOptions options, List<ProjectionField> projectionFields) {
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

        if (sortFields != null) {
            List<Sort> sorts = convertToSort(sortFields);
            findOptions = findOptions.sort(sorts.toArray(new Sort[sorts.size()]));
        }

        if (projectionFields != null) {
            findOptions = findOptions.projection().knownFields();
            findOptions = convertToProjection(findOptions, projectionFields);
        }

        return findOptions;
    }

    @Override
    public List<T> getListByQuery(Datastore datastore, int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {

        if (skip < 0 || limit < 0) {
            throw new IllegalArgumentException("skip and or limit can not be negative");
        }

        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get());
        } else {
            Log.info("Context not set?");
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        MorphiaCursor<T> cursor;

        FindOptions findOptions = buildFindOptions(skip, limit, sortFields, projectionFields);

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query);
                filters.add(Filters.and(filter));
            }
            Filter[] filterArray = new Filter[filters.size()];
            Query<T> q;

            cursor = datastore.find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(findOptions);
        } else {
            Filter[] filterArray = new Filter[filters.size()];
            Query<T> q;

            cursor = datastore.find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(findOptions);
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
    public List<T> getListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields) {
        return getListByQuery(dataStore.getDataStore(getSecurityContextRealmId()), skip, limit, query, sortFields, projectionFields);
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
            cursor = dataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass())
                    .filter(filters.toArray(filterArray))
                    .iterator(new FindOptions().skip(skip).limit(limit));
        } else {

            cursor = dataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass())
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

    @Override
    public long getCount(@Nullable String query) {
        return getCount(dataStore.getDataStore(getSecurityContextRealmId()), query);
    }


    @Override
    public long getCount(Datastore datastore, @Nullable String query) {
        List<Filter> filters = new ArrayList<>();

        if (SecurityContext.getResourceContext().isPresent() && SecurityContext.getPrincipalContext().isPresent()) {
            filters = ruleContext.getFilters(filters,
                    SecurityContext.getPrincipalContext().get(),
                    SecurityContext.getResourceContext().get());
        } else {
            throw new RuntimeException("Resource Context is not set in thread, check security configuration");
        }

        if (query != null && !query.isEmpty()) {
            String cleanQuery = query.trim();
            if (!cleanQuery.isEmpty()) {
                Filter filter = MorphiaUtils.convertToFilter(query);
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
    // TODO consider breaking this apart into to seperate classes and injecting them seperately so you can
    // have different implementations for read vs. write.

    public MorphiaSession startSession(String realm) {
        return dataStore.getDataStore(realm).startSession();
    }

    public T save(MorphiaSession session, T value) {
        return session.save(value);
    }

    @Override
    public T save(T value) {
        //ruleContext.check(SecurityContext.getPrincipalContext().get(), SecurityContext.getResourceContext().get());
        return this.save(getSecurityContextRealmId(), value);
    }


    @Override
    public List<T> save(List<T> entities) {
        return save(dataStore.getDataStore(getSecurityContextRealmId()),entities);
    }

    @Override
    public List<T> save(Datastore datastore, List<T> entities) {
        return datastore.save(entities);
    }

    @Override
    public T save(String realmId, T value) {
        return save(dataStore.getDataStore(realmId), value);
    }

    @Override
    public T save(Datastore datastore, T value) {
        return datastore.save(value);
    }



    public void removeReferenceConstraint(T obj, MorphiaSession session) {
        Mapper mapper = dataStore.getDataStore(getSecurityContextRealmId()).getMapper();
        EntityModel mappedClass = mapper.getEntityModel(obj.getClass());

        for (PropertyModel mappedField : mappedClass.getProperties()) {
            if (mappedField.isReference()) {
                if (BaseModel.class.isAssignableFrom(mappedField.getEntityModel().getType())) {
                    BaseModel baseModel = (mappedField.getAccessor().get(obj) != null) ? (BaseModel) mappedField.getAccessor().get(obj) : null;
                    if (baseModel != null) {
                        if (!baseModel.getReferences().contains(obj)) {
                            baseModel.getReferences().remove(obj);
                            session.save(mappedField.getAccessor().get(obj));
                        }
                    }
                }
            }
        }
    }

    @Override
    public long delete(T obj) {
        DeleteResult result;
        if (obj.getReferences() == null || obj.getReferences().isEmpty()) {
            try (MorphiaSession s = dataStore.getDataStore(getSecurityContextRealmId()).startSession()) {
                s.startTransaction();
                removeReferenceConstraint(obj, s);
                result = s.delete(obj);
                s.commitTransaction();
            }
            return result.getDeletedCount();
        } else {
            Set<ReferenceEntry> entriesToRemove = new HashSet<>();
            // Iterate through references in this class and ensure that there are actually references and that the list of references is not stale
            for (ReferenceEntry reference : obj.getReferences()) {
                try (MorphiaSession s = dataStore.getDataStore(getSecurityContextRealmId()).startSession()) {
                    s.startTransaction();
                    try {
                        Class<?> clazz = Class.forName(reference.getType());
                        Query<?> q = s.find(clazz).filter(Filters.eq("_id", reference.getReferencedId()));
                        if (q.count() == 0) {
                            entriesToRemove.add(reference);
                        }
                    } catch (ClassNotFoundException e) {
                        entriesToRemove.add(reference);
                    }
                }
            }
            obj.getReferences().removeAll(entriesToRemove);

            if (obj.getReferences().isEmpty()) {
                try (MorphiaSession s = dataStore.getDataStore(getSecurityContextRealmId()).startSession()) {
                    s.startTransaction();
                    removeReferenceConstraint(obj, s);
                    result = s.delete(obj);
                    s.commitTransaction();
                }
                return result.getDeletedCount();
            } else {
                throw new IllegalStateException("Can not delete object because it has references from other objects to this one that would corrupt the relationship.  Check references attribute to see what objects reference this one: ");
            }
        }
    }

    @Override
    public long delete(MorphiaSession s, T obj) {
        DeleteResult result;
        if (obj.getReferences() == null || obj.getReferences().isEmpty()) {
            removeReferenceConstraint(obj, s);
            result = s.delete(obj);
            return result.getDeletedCount();
        } else {
            Set<ReferenceEntry> entriesToRemove = new HashSet<>();
            // Iterate through references in this class and ensure that there are actually references and that the list of references is not stale
            for (ReferenceEntry reference : obj.getReferences()) {
                try {
                    Class<?> clazz = Class.forName(reference.getType());
                    Query<?> q = s.find(clazz).filter(Filters.eq("_id", reference.getReferencedId()));
                    if (q.count() == 0) {
                        entriesToRemove.add(reference);
                    }
                } catch (ClassNotFoundException e) {
                    entriesToRemove.add(reference);
                }
            }
            obj.getReferences().removeAll(entriesToRemove);

            if (obj.getReferences().isEmpty()) {
                    removeReferenceConstraint(obj, s);
                    result = s.delete(obj);
                    s.commitTransaction();
                return result.getDeletedCount();
            } else {
                throw new IllegalStateException("Can not delete object because it has references from other objects to this one that would corrupt the relationship.  Check references attribute to see what objects reference this one: ");
            }
        }
    }


    @Override
    @SafeVarargs
    public final long update(@NotNull String id, @NotNull Pair<String, Object>... pairs) {
        ObjectId oid = new ObjectId(id);
        return update(oid, pairs);
    }


    @Override
    public long update (@NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) {
        return update(dataStore.getDataStore(getSecurityContextRealmId()), id, pairs);
    }

    @Override
    public long update(Datastore datastore, @NotNull String id, @NotNull Pair<String, Object>... pairs) {
        ObjectId oid = new ObjectId(id);
        return update(datastore, oid, pairs);
    }

    public long update(Datastore datastore, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) {
        List<UpdateOperator> updateOperators = new ArrayList<>();
        for (Pair<String, Object> pair : pairs) {
            // check that the pair key corresponds to a field in the persistent class that is an enum
            Field field = null;
            try {
                field = getPersistentClass().getDeclaredField(pair.getKey());
                Reference ref = field.getAnnotation(Reference.class);
                if (ref!= null) {
                    //TODO fix this case where there is an update to a reference field
                    Log.warn("Update to class that contains references");
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


        Update<T> update = datastore.find(getPersistentClass()).filter(Filters.eq("_id", id))
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

            alist.forEach((UIAction action) -> {

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


}
