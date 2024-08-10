package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.rest.models.Collection;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import dev.morphia.query.filters.Filter;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public interface BaseRepo<T extends BaseModel> {
   public Class<T> getPersistentClass();

   public Optional<T> findById(String id);
   public Optional<T> findById(ObjectId id);
   public Optional<T> findByRefName (String refId);
   public JsonSchema getSchema();

   public List<T> getAllList();

   public List<T> getListByQuery(int skip, int limit, String query);
   public List<T> getListByQuery(int skip, int limit, String query, List<SortField> sortFields, List<String> projectedProperties);
   public List<T>  getList(int skip, int limit, List<String> columns, List<Filter> filters, List<SortField> sortFields);
   public T save(T value);

   public T save(String realm, T value);

   public long delete(T obj);

   public long update (@NotNull String id, @NotNull Pair<String, Object>... pairs);
   public long update (@NotNull ObjectId id, @NotNull Pair<String, Object>... pairs);

   public T fillUIActions(@NotNull T model);

   Collection<T> fillUIActions(@NotNull Collection<T> collection);

}