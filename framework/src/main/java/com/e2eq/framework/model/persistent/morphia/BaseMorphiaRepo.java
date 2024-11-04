package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.rest.models.Collection;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filter;
import dev.morphia.transactions.MorphiaSession;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public interface BaseMorphiaRepo<T extends BaseModel> {
   public Class<T> getPersistentClass();
   // UI Actions
   public T fillUIActions(@NotNull T model);
   Collection<T> fillUIActions(@NotNull Collection<T> collection);

   // Read based API's
   public Optional<T> findById(String id);
   public Optional<T> findById(Datastore s, ObjectId id);
   public Optional<T> findById(ObjectId id);
   public Optional<T> findByRefName (String refId);

   public JsonSchema getSchema();

   public List<T> getAllList();
   public List<T> getListByQuery(int skip, int limit, String filter);
   public List<T> getListByQuery(int skip, int limit, String filter, List<SortField> sortFields, List<ProjectionField> projectedProperties);
   public List<T>  getList(int skip, int limit, List<Filter> filters, List<SortField> sortFields);
   public List<T> getListByQuery(Datastore datastore, int skip, int limit, String query, List<SortField> sortFields, List<ProjectionField> projectionFields);

   public long getCount(String filter);
   public long getCount(Datastore datastore,String filter);

   // Write based API's
   public T save(T value);
   public T save(String realm, T value);
   public T save(Datastore datastore, T value);
   public T save(MorphiaSession session, T value);
   public List<T> save(List<T> entities);
   public List<T> save(Datastore datastore, List<T> entities);
   public List<T> save(MorphiaSession datastore, List<T> entities);

   public long delete(T obj);
   public long delete(MorphiaSession s, T obj);


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
