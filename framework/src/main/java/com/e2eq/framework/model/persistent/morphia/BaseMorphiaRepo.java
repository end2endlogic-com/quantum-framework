package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.rest.models.Collection;
import com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchema;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filter;
import dev.morphia.transactions.MorphiaSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.PathParam;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public interface BaseMorphiaRepo<T extends UnversionedBaseModel> {
   Class<T> getPersistentClass();
   // UI Actions
   T fillUIActions(@NotNull T model);
   Collection<T> fillUIActions(@NotNull Collection<T> collection);

   // Read based API's
   Optional<T> findById(@NotNull String id);
   Optional<T> findById(@NotNull Datastore s, @NotNull ObjectId id);
   Optional<T> findById(@NotNull ObjectId id);

   Optional<T> findByRefName(@NotNull Datastore datastore, @NotNull String refName);
   Optional<T> findByRefName (@NotNull String refId);

   JsonSchema getSchema();

   /**
    *  This method is used to get a list of all entities of the given type.
    * It includses the datastore so you can specify which datastore to use, as well use it for transactional operations.
    * @return List of all entities of the given type
    */
   List<T> getAllList();

   /**
    *    * This method is used to get a list of entities based on the given query, skip, limit, and filter.
    * @param datastore
    * @return
    */
   List<T> getAllList(Datastore datastore);
   /**
    *
    * @param skip must be 0 or greater
    * @param limit can be 0 or a negative number in which case  all records are returned,
    *              a positive number and only the amount specified will be returned
    * @param filter can be null but if given must follow Filter syntax
    * @return
    */
   List<T> getListByQuery(int skip, int limit, @Nullable String filter);

   /**
    * This method is used to get a list of entities based on the given query, skip, limit, sort fields, and projected properties.
    * It allows for flexible querying and projection of data.
    * @param skip must be 0 or greater
    * @param limit can be 0 or a negative number in which case  all records are returned,
    *              a positive number and only the amount specified will be returned
    * @param filter can be null but if given must follow Filter syntax
    * @param sortFields    can be null but if given must follow SortField syntax
    * @param projectedProperties can be null but if given must follow ProjectionField syntax
    * @return
    */
   List<T> getListByQuery(int skip, int limit, @Nullable String filter, List<SortField> sortFields, List<ProjectionField> projectedProperties);

   /**
    * This method is used to get a list of entities based on the given query, skip, limit, sort fields, and projected properties.
    * It allows for flexible querying and projection of data.  It includses the datastore so you can
    * specify which datastore to use, as well use it for transactional operations.
    * @param datastore The datastore to use for the query
    * @param skip must be 0 or greater
    * @param limit can be 0 or a negative number in which case  all records are returned,
    *              a positive number and only the amount specified will be returned
    * @param query can be null but if given must follow Filter syntax
    * @param sortFields    can be null but if given must follow SortField syntax
    * @param projectionFields can be null but if given must follow ProjectionField syntax
    * @return List of entities matching the given criteria
    */
   List<T> getListByQuery(@NotNull Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, List<ProjectionField> projectionFields);

   /**
    * This method is used to get a list of entities based on the given filters and sort fields
    * @param skip    must be 0 or greater
    * @param limit   can be 0 or a negative number in which case  all records are returned,
    *                a positive number and only the amount specified will be returned
    * @param filters can be null but if given must follow Filter syntax
    * @param sortFields can be null but if given must follow SortField syntax
    * @return List of entities matching the given criteria
    */
   List<T> getList(int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields);

   /**
    * This method is used to get a list of entities based on the given filters and sort fields
    * It includes the datastore so you can specify which datastore to use, as well use it for transactional operations.
    * @param datastore  The datastore to use for the query
    * @param skip must be 0 or greater
    * @param limit can be 0 or a negative number in which case  all records are returned,
    *              a positive number and only the amount specified will be returned
    * @param filters    can be null but if given must follow Filter syntax
    * @param sortFields    can be null but if given must follow SortField syntax
    * @return List of entities matching the given criteria
    */
   List<T> getList(Datastore datastore, int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields);

   /**
    * This method is used to get a list of entities based on the given query, filter,
    * @param ids  can be null but if given must follow Filter syntax
    * @return  List of entities matching the given criteria
    */
   List<T> getListFromIds(@NotNull(value="List of objectids can not be null") @NotEmpty (message = "list of ids can not be empty") List<ObjectId> ids);
   List<T> getListFromIds(Datastore datastore,@NotNull(value="List of objectids can not be null") @NotEmpty (message = "list of ids can not be empty") List<ObjectId> ids);
   List<T> getListFromRefNames(List<String> refNames);


   List<T> getListFromRefNames(Datastore datastore, List<String> refNames);
   List<EntityReference> getEntityReferenceListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields);
   List<EntityReference> getEntityReferenceListByQuery(Datastore datastore, int skip, int limit, @Nullable String query, List<SortField> sortFields);
   List<T> getListFromReferences(List<EntityReference> references);
   List<T> getListFromReferences(Datastore datastore, List<EntityReference> references);

   CloseableIterator<T> getStreamByQuery(int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields);
   CloseableIterator<T> getStreamByQuery(Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields);

   long getCount(@Nullable String filter);
   long getCount(@NotNull Datastore datastore,@Nullable String filter);

   // Write based API's
   T save(@Valid T value);
   T save(@NotNull String realm, @Valid T value);
   T save(@NotNull Datastore datastore, @Valid T value);
   T save(@NotNull MorphiaSession session, @Valid T value);

   List<T> save(List<T> entities);
   List<T> save(@NotNull Datastore datastore, List<T> entities);
   List<T> save(@NotNull MorphiaSession datastore, List<T> entities);

   long delete(T obj) throws ReferentialIntegrityViolationException;
   long delete(@NotNull ObjectId id) throws ReferentialIntegrityViolationException;
   long delete(@NotNull Datastore datastore, T aobj) throws ReferentialIntegrityViolationException;
   long delete(@NotNull MorphiaSession s, T obj) throws ReferentialIntegrityViolationException;



   long updateActiveStatus (@PathParam("id") ObjectId id, boolean active);
   long updateActiveStatus (Datastore datastore, @PathParam("id") ObjectId id, boolean active);

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
