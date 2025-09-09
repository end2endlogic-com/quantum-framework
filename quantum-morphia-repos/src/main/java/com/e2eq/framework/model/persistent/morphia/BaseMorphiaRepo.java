package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
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

/**
 * Base repository contract for Morphia-backed persistence of {@link UnversionedBaseModel} entities.
 *
 * <p>This interface exposes common read/write operations, with overloads that:
 * <ul>
 *   <li>Accept a realm identifier (multi-tenancy/partitioning support).</li>
 *   <li>Accept an explicit {@link dev.morphia.Datastore} or {@link dev.morphia.transactions.MorphiaSession}
 *       to bind operations to a particular datastore/transactional context.</li>
 *   <li>Support filtering, sorting, projection and streaming for large result sets.</li>
 * </ul>
 *
 * <p>Implementations are expected to:
 * <ul>
 *   <li>Enforce Jakarta validation annotations present on method parameters.</li>
 *   <li>Respect referential integrity and throw {@link com.e2eq.framework.exceptions.ReferentialIntegrityViolationException}
 *       when deletes would violate dependencies.</li>
 *   <li>Honor state-transition rules by throwing {@link com.e2eq.framework.model.persistent.InvalidStateTransitionException}
 *       where applicable (e.g., update operations that would move an entity to an invalid state).</li>
 * </ul>
 *
 * @param <T> the concrete entity type handled by the repository
 */
public interface BaseMorphiaRepo<T extends UnversionedBaseModel> {

   /**
    * Returns the logical database name backing this repository.
    *
    * @return the database name
    */
   String getDatabaseName ();

   /**
    * Returns the persistent entity class handled by this repository.
    *
    * @return the entity class
    */
   Class<T> getPersistentClass();

   // UI Actions

   /**
    * Populates UI-specific actions or metadata on the provided model instance.
    * Implementations may compute allowed operations based on user/realm/context.
    *
    * @param model the entity to enrich; must not be null
    * @return the same instance with UI actions populated
    */
   T fillUIActions(@NotNull T model);

   /**
    * Populates UI-specific actions or metadata across all items in the provided collection.
    *
    * @param collection the collection wrapper containing items to enrich; must not be null
    * @return the same collection with each item’s UI actions populated
    */
   Collection<T> fillUIActions(@NotNull Collection<T> collection);

   /**
    * Ensures required indexes exist for a collection within the given realm.
    * Implementations may create missing indexes idempotently.
    *
    * @param realmId    realm/tenant identifier; may be null for default realm depending on implementation
    * @param collection the collection name to validate/index
    */
   void ensureIndexes(String realmId, String collection);

   // Read based API's

   /**
    * Finds an entity by its string identifier in the default/current realm.
    * @param id the entity id; must not be null
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findById(@NotNull String id);

   /**
    * Finds an entity by its string identifier within a specific realm.
    * @param id      the entity id; must not be null
    * @param realmId the realm identifier; may be null for default realm depending on implementation
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findById(@NotNull String id, String realmId);

   /**
    * Finds an entity by its {@link ObjectId} in the default/current realm.
    * @param id the object id; must not be null
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findById(@NotNull ObjectId id);

   /**
    * Finds an entity by its {@link ObjectId} within a specific realm.
    * @param id      the object id; must not be null
    * @param realmId the realm identifier; may be null for default realm depending on implementation
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findById (@NotNull ObjectId id, String realmId);

   /**
    * Finds an entity by its {@link ObjectId} within a specific realm.
    * @param id      the object id; must not be null
    * @param realmId the realm identifier; may be null for default realm depending on implementation
    * @param ignoreRules whether to ignore rules for reference name/alias resolution
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findById (@NotNull ObjectId id, String realmId, boolean ignoreRules);

   /**
    * Finds an entity by its {@link ObjectId} using an explicit datastore.
    * @param s  the datastore to use; must not be null
    * @param id the object id; must not be null
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findById(@NotNull Datastore s, @NotNull ObjectId id);

   /**
    * Finds an entity by its {@link ObjectId} using an explicit datastore.
    * @param datastore  the datastore to use; must not be null
    * @param id the object id; must not be null
    * @param ignoreRules whether to ignore rules for reference name/alias resolution
    * @return an {@link Optional} with the entity if found
    */
   public Optional<T> findById(@NotNull Datastore datastore, @NotNull ObjectId id, boolean ignoreRules);


   /**
    * Finds an entity by its reference name/alias in the default/current realm.
    * @param refId the reference value (e.g., business key); must not be null
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findByRefName (@NotNull String refId);

   /**
    * Finds an entity by its reference name/alias within the given realm.
    * @param refName the reference value; must not be null
    * @param realmId the realm identifier
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findByRefName (@NotNull String refName, String realmId);

   /**
    * Finds an entity by its reference name/alias using an explicit datastore.
    * @param datastore the datastore to use; must not be null
    * @param refName   the reference value; must not be null
    * @return an {@link Optional} with the entity if found
    */
   Optional<T> findByRefName(@NotNull Datastore datastore, @NotNull String refName);

   /**
    * Finds an entity by its reference name/alias using an explicit datastore.
    * @param datastore the datastore to use; must not be null
    * @param refName   the reference value; must not be null
    * @param ignoreRules whether to ignore rules for reference name/alias resolution
    * @return an {@link Optional} with the entity if found
    */
   public Optional<T> findByRefName(@NotNull Datastore datastore, @NotNull String refName, boolean ignoreRules);

   /**
    * Returns a JSON Schema representation of the entity for validation and UI-generation.
    *
    * @return the schema for the entity type
    */
   JsonSchema getSchema();

   /**
    * Returns all entities in the default/current realm.
    * @return list of all entities
    */
   List<T> getAllList();

   /**
    * Returns all entities within a specific realm.
    * @param realmId the realm identifier
    * @return list of all entities in the realm
    */
   List<T> getAllList (String realmId);

   /**
    * Returns all entities using an explicit datastore.
    * @param datastore the datastore to use
    * @return list of all entities
    */
   List<T> getAllList(Datastore datastore);

   /**
    * Retrieves a list of entities using optional paging and filtering.
    *
    * @param skip   must be {@code 0} or greater
    * @param limit  can be {@code 0} or a negative number in which case all records are returned,
    *               a positive number and only the amount specified will be returned
    * @param filter can be {@code null} but if given must follow Filter syntax
    * @return list of matching entities
    */
   List<T> getListByQuery(int skip, int limit, @Nullable String filter);

   /**
    * Returns a list of entities for the provided query with optional sorting and projection.
    *
    * @param skip                 must be {@code 0} or greater
    * @param limit                can be {@code 0} or a negative number in which case all records are returned,
    *                             a positive number and only the amount specified will be returned
    * @param filter               can be {@code null} but if given must follow Filter syntax
    * @param sortFields           can be {@code null} but if given must follow SortField syntax
    * @param projectedProperties  can be {@code null} but if given must follow ProjectionField syntax
    * @return list of matching entities
    */
   List<T> getListByQuery(int skip, int limit, @Nullable String filter, List<SortField> sortFields, List<ProjectionField> projectedProperties);

   /**
    * Returns a list of entities for the provided query within a realm, with optional sorting and projection.
    *
    * @param realmId             the realm identifier
    * @param skip                must be {@code 0} or greater
    * @param limit               {@code 0} or negative to return all; positive for page size
    * @param query               may be {@code null}; must follow the repository’s filter DSL when provided
    * @param sortFields          optional sort specification
    * @param projectionFields    optional projection selection
    * @return list of matching entities
    */
   List<T> getListByQuery (String realmId, int skip, int limit, @Nullable String query, List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields);

   /**
    * Returns a list of entities based on the given query and options using an explicit datastore.
    * It allows for flexible querying and projection of data.
    *
    * @param datastore         The datastore to use for the query
    * @param skip              must be 0 or greater
    * @param limit             can be 0 or a negative number in which case all records are returned,
    *                         a positive number returns only that amount
    * @param query             can be null but if given must follow Filter syntax
    * @param sortFields        can be null but if given must follow SortField syntax
    * @param projectionFields  can be null but if given must follow ProjectionField syntax
    * @return List of entities matching the given criteria
    */
   List<T> getListByQuery(@NotNull Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, List<ProjectionField> projectionFields);

   /**
    * Returns entities based on the given filters and sort fields in the default realm.
    * @param skip    must be 0 or greater
    * @param limit   can be 0 or negative to return all; positive for page size
    * @param filters can be null but if given must follow Filter syntax
    * @param sortFields can be null but if given must follow SortField syntax
    * @return List of entities matching the criteria
    */
   List<T> getList(int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields);

   /**
    * Returns entities based on filters and sort within the specified realm.
    * @param realmId   the realm identifier
    * @param skip      must be 0 or greater
    * @param limit     can be 0 or negative to return all; positive for page size
    * @param filters   optional filters; when provided must follow Filter syntax
    * @param sortFields optional sort specification
    * @return list of entities matching the criteria
    */
   List<T> getList (String realmId, int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields);

   /**
    * Returns entities based on the given filters and sort fields using an explicit datastore.
    * @param datastore  The datastore to use for the query
    * @param skip       must be 0 or greater
    * @param limit      can be 0 or negative to return all; positive for page size
    * @param filters    can be null but if given must follow Filter syntax
    * @param sortFields can be null but if given must follow SortField syntax
    * @return List of entities matching the given criteria
    */
   List<T> getList(Datastore datastore, int skip, int limit, @Nullable List<Filter> filters, @Nullable List<SortField> sortFields);

   /**
    * Retrieves a list of entities by their identifiers within the provided realm.
    *
    * @param realmId the realm identifier, must not be {@code null}
    * @param ids     list of entity ids, must not be empty
    * @return list of matching entities
    */
   List<T> getListFromIds(@NotNull(value="RealmId can not be null") String realmId,
                          @NotNull(value="List of objectids can not be null")
                          @NotEmpty(message = "list of ids can not be empty") List<ObjectId> ids);

   /**
    * Retrieves entities by their identifiers in the default realm.
    * @param ids list of entity ids; must not be empty
    * @return list of matching entities
    */
   List<T> getListFromIds(@NotNull(value="List of objectids can not be null") @NotEmpty (message = "list of ids can not be empty") List<ObjectId> ids);

   /**
    * Retrieves entities by their identifiers using an explicit datastore.
    * @param datastore the datastore to use
    * @param ids list of entity ids; must not be empty
    * @return list of matching entities
    */
   List<T> getListFromIds(Datastore datastore,@NotNull(value="List of objectids can not be null") @NotEmpty (message = "list of ids can not be empty") List<ObjectId> ids);

   /**
    * Returns entities resolved by their reference names within a specific realm.
    * @param realmId the realm identifier
    * @param refNames the list of reference names/aliases
    * @return list of matching entities
    */
   List<T> getListFromRefNames(String realmId,List<String> refNames);
   /**
    * Returns entities resolved by their reference names in the default realm.
    * @param refNames the list of reference names/aliases
    * @return list of matching entities
    */
   List<T> getListFromRefNames(List<String> refNames);
   /**
    * Returns entities resolved by their reference names using an explicit datastore.
    * @param datastore the datastore to use
    * @param refNames the list of reference names/aliases
    * @return list of matching entities
    */
   List<T> getListFromRefNames(Datastore datastore, List<String> refNames);

   /**
    * Returns lightweight {@link EntityReference} records by query within a realm.
    * @param realmId the realm identifier
    * @param skip offset; must be 0 or greater
    * @param limit page size; 0 or negative for all
    * @param query optional filter query
    * @param sortFields optional sort specification
    * @return list of matching references
    */
   List<EntityReference> getEntityReferenceListByQuery(String realmId, int skip, int limit, @Nullable String query, List<SortField> sortFields);
   /**
    * Returns lightweight {@link EntityReference} records by query in the default realm.
    * @param skip offset; must be 0 or greater
    * @param limit page size; 0 or negative for all
    * @param query optional filter query
    * @param sortFields optional sort specification
    * @return list of matching references
    */
   List<EntityReference> getEntityReferenceListByQuery(int skip, int limit, @Nullable String query, List<SortField> sortFields);
   /**
    * Returns lightweight {@link EntityReference} records by query using an explicit datastore.
    * @param datastore the datastore to use
    * @param skip offset; must be 0 or greater
    * @param limit page size; 0 or negative for all
    * @param query optional filter query
    * @param sortFields optional sort specification
    * @return list of matching references
    */
   List<EntityReference> getEntityReferenceListByQuery(Datastore datastore, int skip, int limit, @Nullable String query, List<SortField> sortFields);

   /**
    * Resolves full entities from a list of {@link EntityReference} objects within a realm.
    * @param realmId the realm identifier
    * @param references the references to resolve
    * @return list of resolved entities
    */
   List<T> getListFromReferences(String realmId,List<EntityReference> references);
   /**
    * Resolves full entities from a list of {@link EntityReference} objects in the default realm.
    * @param references the references to resolve
    * @return list of resolved entities
    */
   List<T> getListFromReferences(List<EntityReference> references);
   /**
    * Resolves full entities from a list of {@link EntityReference} objects using an explicit datastore.
    * @param datastore the datastore to use
    * @param references the references to resolve
    * @return list of resolved entities
    */
   List<T> getListFromReferences(Datastore datastore, List<EntityReference> references);

   /**
    * Streams matching entities for the provided query, suitable for large datasets.
    * The caller is responsible for closing the returned iterator.
    * @param skip offset; must be 0 or greater
    * @param limit page size; 0 or negative for all
    * @param query optional filter query
    * @param sortFields optional sort specification
    * @param projectionFields optional projection fields
    * @return a closeable iterator over matching entities
    */
  CloseableIterator<T> getStreamByQuery(int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields);

  /**
    * Streams matching entities using an explicit datastore. The caller must close the iterator.
    * @param datastore the datastore to use
    * @param skip offset; must be 0 or greater
    * @param limit page size; 0 or negative for all
    * @param query optional filter query
    * @param sortFields optional sort specification
    * @param projectionFields optional projection fields
    * @return a closeable iterator over matching entities
    */
  CloseableIterator<T> getStreamByQuery(Datastore datastore, int skip, int limit, @Nullable String query, @Nullable List<SortField> sortFields, @Nullable List<ProjectionField> projectionFields);

   /**
    * Returns the count of matching entities within the specified realm.
    * @param realmId the realm identifier
    * @param filter optional filter query
    * @return count of matching entities
    */
   long getCount(String realmId, @Nullable String filter);
   /**
    * Returns the count of matching entities in the default realm.
    * @param filter optional filter query
    * @return count of matching entities
    */
   long getCount(@Nullable String filter);
   /**
    * Returns the count of matching entities using an explicit datastore.
    * @param datastore the datastore to use
    * @param filter optional filter query
    * @return count of matching entities
    */
   long getCount(@NotNull Datastore datastore,@Nullable String filter);

   // Write based API's

   /**
    * Persists a new or existing entity in the default realm.
    * Implementations may insert or upsert depending on identifier presence.
    * @param value the entity to persist
    * @return the saved entity (possibly with identifier assigned)
    */
  T save(@Valid T value);

  /**
    * Persists a new or existing entity within the specified realm.
    * @param realm the realm identifier
    * @param value the entity to persist
    * @return the saved entity (possibly with identifier assigned)
    */
  T save(@NotNull String realm, @Valid T value);

  /**
    * Persists a new or existing entity using an explicit datastore.
    * @param datastore the datastore to use
    * @param value the entity to persist
    * @return the saved entity (possibly with identifier assigned)
    */
  T save(@NotNull Datastore datastore, @Valid T value);

  /**
    * Persists a new or existing entity within an explicit session/transaction.
    * @param session the session to use
    * @param value the entity to persist
    * @return the saved entity (possibly with identifier assigned)
    */
  T save(@NotNull MorphiaSession session, @Valid T value);

   /**
    * Batch-save a list of entities in the default realm.
    * @param entities the entities to persist
    * @return the saved entities (possibly with identifiers assigned)
    */
   List<T> save(List<T> entities);
   /**
    * Batch-save a list of entities using an explicit datastore.
    * @param datastore the datastore to use
    * @param entities the entities to persist
    * @return the saved entities (possibly with identifiers assigned)
    */
   List<T> save(@NotNull Datastore datastore, List<T> entities);
   /**
    * Batch-save a list of entities within an explicit session/transaction.
    * @param datastore the session to use
    * @param entities the entities to persist
    * @return the saved entities (possibly with identifiers assigned)
    */
   List<T> save(@NotNull MorphiaSession datastore, List<T> entities);

   /**
    * Deletes the provided entity within the specified realm.
    * @param realm the realm identifier
    * @param obj the entity to delete
    * @return the number of deleted records (0 or 1)
    * @throws ReferentialIntegrityViolationException if the entity is referenced elsewhere
    */
  long delete(@NotNull String realm, T obj) throws ReferentialIntegrityViolationException;

   /**
    * Deletes the provided entity within the specified realm.
    * @param obj the entity to delete
    * @return the number of deleted records (0 or 1)
    * @throws ReferentialIntegrityViolationException if the entity is referenced elsewhere
    */
  long delete(T obj) throws ReferentialIntegrityViolationException;

   /**
    * Deletes the provided entity within the specified realm.
    * @param id the entity id
    * @return the number of deleted records (0 or 1)
    * @throws ReferentialIntegrityViolationException if the entity is referenced elsewhere
    */
  long delete(@NotNull ObjectId id) throws ReferentialIntegrityViolationException;

   /**
    * Deletes the entity by id within the given realm.
    * @param realmId the realm identifier
    * @param id the entity id
    * @return the number of deleted records (0 or 1)
    * @throws ReferentialIntegrityViolationException if the entity is referenced elsewhere
    */
   long delete (@NotNull String realmId, @NotNull ObjectId id) throws ReferentialIntegrityViolationException;

   /**
    * Deletes the provided entity using an explicit datastore.
    * @param datastore the datastore to use
    * @param aobj the entity to delete
    * @return the number of deleted records (0 or 1)
    * @throws ReferentialIntegrityViolationException if the entity is referenced elsewhere
    */
   long delete(@NotNull Datastore datastore, T aobj) throws ReferentialIntegrityViolationException;
   /**
    * Deletes the provided entity within an explicit session/transaction.
    * @param s the session to use
    * @param obj the entity to delete
    * @return the number of deleted records (0 or 1)
    * @throws ReferentialIntegrityViolationException if the entity is referenced elsewhere
    */
   long delete(@NotNull MorphiaSession s, T obj) throws ReferentialIntegrityViolationException;

   /**
    * Updates the active/soft-delete flag of an entity by id in the default realm.
    * @param id the entity id
    * @param active the desired active status
    * @return the number of affected records (0 or 1)
    */
  long updateActiveStatus (@PathParam("id") ObjectId id, boolean active);
   /**
    * Updates the active/soft-delete flag using an explicit datastore.
    * @param datastore the datastore to use
    * @param id the entity id
    * @param active the desired active status
    * @return the number of affected records (0 or 1)
    */
   long updateActiveStatus (Datastore datastore, @PathParam("id") ObjectId id, boolean active);

   /**
    * Performs a partial update by setting the provided field/value pairs on the entity identified by string id
    * within the specified realm.
    * @return number of affected records (0 or 1)
    * @throws InvalidStateTransitionException if the update violates allowed state transitions
    */
   public long update (String realmId,@NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException;

   /**
    * Partial update by string id in the default realm.
    * @param id the string identifier
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    * @throws InvalidStateTransitionException if the update violates allowed state transitions
    */
   public long update (@NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException;

   /**
    * Partial update by string id using an explicit datastore.
    * @param datastore the datastore to use
    * @param id the string identifier
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    * @throws InvalidStateTransitionException if the update violates allowed state transitions
    */
   public long update(Datastore datastore, @NotNull String id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException;

   /**
    * Partial update by ObjectId within the specified realm.
    * @param realmId the realm identifier
    * @param id the object id
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    * @throws InvalidStateTransitionException if the update violates allowed state transitions
    */
   public long update (String realmId, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException;

   /**
    * Partial update by string id within an explicit session/transaction.
    * @param session the session to use
    * @param id the string identifier
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    */
   public long update(MorphiaSession session, @NotNull String id, @NotNull Pair<String, Object>... pairs);
   /**
    * Partial update by ObjectId in the default realm.
    * @param id the object id
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    * @throws InvalidStateTransitionException if the update violates allowed state transitions
    */
   public long update (@NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException;
   /**
    * Partial update by ObjectId using an explicit datastore.
    * @param datastore the datastore to use
    * @param id the object id
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    * @throws InvalidStateTransitionException if the update violates allowed state transitions
    */
   public long update(Datastore datastore, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException;
   /**
    * Partial update by ObjectId within an explicit session/transaction.
    * @param session the session to use
    * @param id the object id
    * @param pairs field/value pairs to set; must not be null
    * @return number of affected records (0 or 1)
    */
   public long update(MorphiaSession session, @NotNull ObjectId id, @NotNull Pair<String, Object>... pairs);

   /**
    * Merges the detached entity state into persistence context in the default realm.
    * Semantics are similar to JPA merge: fields are reconciled and the persistent instance is returned.
    */
   public T merge(@NotNull T entity);
   /**
    * Same as {@link #merge(UnversionedBaseModel)} using an explicit datastore.
    * @param datastore the datastore to use
    * @param entity the detached entity state to merge
    * @return the persistent instance
    */
   public T merge(Datastore datastore, @NotNull T entity);
   /**
    * Same as {@link #merge(UnversionedBaseModel)} within an explicit session/transaction.
    * @param session the session to use
    * @param entity the detached entity state to merge
    * @return the persistent instance
    */
   public T merge(MorphiaSession session, @NotNull T entity);

   /**
    * Batch-merge a list of detached entities in the default realm.
    * @param entities the entities to merge
    * @return the merged persistent instances
    */
   public List<T> merge(List<T> entities);
   /**
    * Batch-merge using an explicit datastore.
    * @param datastore the datastore to use
    * @param entities the entities to merge
    * @return the merged persistent instances
    */
   public List<T> merge(Datastore datastore, List<T> entities);
   /**
    * Batch-merge within an explicit session/transaction.
    * @param session the session to use
    * @param entities the entities to merge
    * @return the merged persistent instances
    */
   public List<T> merge(MorphiaSession session, List<T> entities);

}
