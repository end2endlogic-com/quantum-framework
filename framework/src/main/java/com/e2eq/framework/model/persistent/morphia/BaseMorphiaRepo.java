package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.CloseableIterator;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.rest.models.Collection;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filter;
import dev.morphia.transactions.MorphiaSession;
import jakarta.validation.Valid;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public interface BaseMorphiaRepo<T extends BaseModel> {
   public Class<T> getPersistentClass();
   // UI Actions
   public T fillUIActions(@NotNull T model);
   Collection<T> fillUIActions(@NotNull Collection<T> collection);

   // Read based API's
   public Optional<T> findById(@NotNull String id);
   public Optional<T> findById(@NotNull Datastore s, @NotNull ObjectId id);
   public Optional<T> findById(@NotNull ObjectId id);
   public Optional<T> findByRefName (@NotNull String refId);

   public JsonSchema getSchema();

   public List<T> getAllList();

   /**
    *
    * @param skip must be 0 or greater
    * @param limit can be 0 or a negative number in which case  all records are returned,
    *              a positive number and only the amount specified will be returned
    * @param filter can be null but if given must follow Filter syntax
    * @return
    */
   public List<T> getListByQuery(int skip, int limit, @Nullable String filter);
   public List<T> getListByQuery(int skip, int limit, @Nullable String filter, List<SortField> sortFields, List<ProjectionField> projectedProperties);
   public List<T>  getList(int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields);
   public List<T> getListByQuery(@NotNull Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, List<ProjectionField> projectionFields);

   public CloseableIterator<T> getStreamByQuery(int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields);

   public long getCount(@Nullable String filter);
   public long getCount(@NotNull Datastore datastore,@Nullable String filter);

   // Write based API's
   public T save(@Valid T value);
   public T save(@NotNull String realm, @Valid T value);
   public T save(@NotNull Datastore datastore, @Valid T value);
   public T save(@NotNull MorphiaSession session, @Valid T value);
   public List<T> save(List<T> entities);
   public List<T> save(@NotNull Datastore datastore, List<T> entities);
   public List<T> save(@NotNull MorphiaSession datastore, List<T> entities);

   public long delete(@Valid T obj) throws ReferentialIntegrityViolationException;
   public long delete(@NotNull MorphiaSession s, @Valid T obj) throws ReferentialIntegrityViolationException;


   public long update (@NotNull String id, @NotNull Pair<String, Object>... pairs);
   public long update(Datastore datastore, @NotNull String id, @NotNull Pair<String, Object>... pairs);
   public long update(MorphiaSession session, @NotNull String id, @NotNull Pair<String, Object>... pairs);
   public long update (@NotNull ObjectId id, @NotNull Pair<String, Object>... pairs);
   public long update(Datastore datastore, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs);
   public long update(MorphiaSession session, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs);

   public T merge(@NotNull T entity);
   public T merge(Datastore datastore, @NotNull T entity);
   public T merge(MorphiaSession session, @NotNull T entity);

   public List<T> merge(List<T> entities);
   public List<T> merge(Datastore datastore, List<T> entities);
   public List<T> merge(MorphiaSession session, List<T> entities);


}
