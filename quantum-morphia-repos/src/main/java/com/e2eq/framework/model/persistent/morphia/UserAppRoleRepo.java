package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationBindingValidator;
import com.e2eq.framework.model.security.UserAppRole;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.mongodb.MongoException;
import com.mongodb.client.model.Indexes;
import dev.morphia.Datastore;
import dev.morphia.UpdateOptions;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application bindings ({@link UserAppRole}), in the system realm next to credentials and realm
 * assignments. Every save applies {@link ApplicationBindingValidator} against the registered
 * application and its installs, normalizes the row, and refuses a second row for the same
 * (subject, application, realm). Who may write which roles is decided by the caller's service
 * before it saves. {@link #upsertById} writes under a caller-supplied id for migrations.
 */
@ApplicationScoped
public class UserAppRoleRepo extends MorphiaRepo<UserAppRole> {

   /**
    * The deployment's membership-administrator roles (the access-invite setting). They confer
    * administrative authority, so no binding grants them ({@link ApplicationBindingValidator#RESERVED_ROLES}).
    */
   @ConfigProperty(name = "quantum.access-invites.membership-admin-roles")
   Optional<List<String>> membershipAdminRoles = Optional.empty();

   /** Roles reserved beyond {@link ApplicationBindingValidator#RESERVED_ROLES}: the configured membership-administrator roles. */
   public List<String> alsoReservedRoles() {
      return membershipAdminRoles == null ? List.of() : membershipAdminRoles.orElse(List.of()).stream()
         .filter(Objects::nonNull).map(String::trim).filter(role -> !role.isEmpty()).toList();
   }

   @Override
   public String getSecurityContextRealmId() {
      return envConfigUtils.getSystemRealm();
   }

   @Override
   public UserAppRole save(Datastore datastore, UserAppRole value) {
      requireValid(datastore, value);
      return super.save(datastore, value);
   }

   @Override
   public UserAppRole save(MorphiaSession session, UserAppRole value) {
      requireValid(session, value);
      return super.save(session, value);
   }

   @Override
   public List<UserAppRole> save(Datastore datastore, List<UserAppRole> entities) {
      entities.forEach(entity -> requireValid(datastore, entity));
      return super.save(datastore, entities);
   }

   @Override
   public List<UserAppRole> save(MorphiaSession session, List<UserAppRole> entities) {
      entities.forEach(entity -> requireValid(session, entity));
      return super.save(session, entities);
   }

   /**
    * Inserts the binding under its id, or replaces the stored row with that id. The id is
    * required. Idempotent: writing the same binding twice leaves one row.
    */
   public UserAppRole upsertById(UserAppRole binding) {
      Objects.requireNonNull(binding, "binding");
      if (binding.getId() == null) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, "an upsert needs the binding's id");
      }
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
      UserAppRole stored = datastore.find(UserAppRole.class).filter(Filters.eq("_id", binding.getId())).first();
      binding.setVersion(stored == null ? null : stored.getVersion());
      return save(datastore, binding);
   }

   public Optional<UserAppRole> findByIdWithIgnoreRules(ObjectId id) {
      return Optional.ofNullable(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
         .find(UserAppRole.class).filter(Filters.eq("_id", id)).first());
   }

   /**
    * Bindings of a user and of the given groups for an application, in one realm or, when
    * {@code realmRefName} is null, in every realm. Whatever their status: the caller decides.
    */
   public List<UserAppRole> findForSubjectsWithIgnoreRules(String userId, Collection<String> groupRefNames,
                                                           String applicationId, String realmRefName) {
      List<Filter> subjects = new ArrayList<>();
      if (userId != null && !userId.isBlank()) {
         subjects.add(Filters.eq("userId", userId));
      }
      if (groupRefNames != null && !groupRefNames.isEmpty()) {
         subjects.add(Filters.in("groupRefName", groupRefNames));
      }
      if (subjects.isEmpty() || applicationId == null || applicationId.isBlank()) {
         return List.of();
      }
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("applicationId", applicationId.trim()));
      if (realmRefName != null) {
         filters.add(Filters.eq("realmRefName", realmRefName.trim()));
      }
      filters.add(subjects.size() == 1 ? subjects.get(0) : Filters.or(subjects.toArray(Filter[]::new)));
      try (var cursor = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
            .find(UserAppRole.class)
            .filter(filters.toArray(Filter[]::new))
            .iterator()) {
         return cursor.toList();
      }
   }

   /** Bindings for an application in a realm, optionally only those naming a tenant. */
   public List<UserAppRole> findByApplicationWithIgnoreRules(String applicationId, String realmRefName, String tenantId) {
      List<Filter> filters = new ArrayList<>(List.of(
         Filters.eq("applicationId", applicationId), Filters.eq("realmRefName", realmRefName)));
      if (tenantId != null) {
         filters.add(Filters.eq("tenantIds", tenantId));
      }
      try (var cursor = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
            .find(UserAppRole.class)
            .filter(filters.toArray(Filter[]::new))
            .iterator()) {
         return cursor.toList();
      }
   }

   /**
    * Suspends the active group bindings of an application that grant only {@code tenantId} in the
    * realm, marking each {@code suspendedBy = uninstall:<installId>}: what uninstalling the
    * application for that tenant does to them. Nothing is deleted, whoever wrote the binding.
    * Groups never span tenants.
    */
   public long suspendGroupBindingsForUninstall(String applicationId, String realmRefName, String tenantId,
                                                ObjectId installId) {
      return groupBindingsOf(applicationId, realmRefName, tenantId)
         .filter(Filters.in("status", java.util.Arrays.asList(UserAppRole.STATUS_ACTIVE, null)))
         .update(new UpdateOptions().multi(true),
            UpdateOperators.set("status", UserAppRole.STATUS_SUSPENDED),
            UpdateOperators.set("suspendedBy", UserAppRole.SUSPENDED_BY_UNINSTALL + installId),
            UpdateOperators.inc("version", 1))
         .getModifiedCount();
   }

   /**
    * Reactivates exactly the group bindings that an uninstall of one of {@code uninstalledIds}
    * (installs of this application for this tenant) suspended; bindings suspended for any other
    * reason stay suspended.
    */
   public long reactivateGroupBindingsAfterReinstall(String applicationId, String realmRefName, String tenantId,
                                                     Collection<ObjectId> uninstalledIds) {
      if (uninstalledIds == null || uninstalledIds.isEmpty()) {
         return 0;
      }
      List<String> markers = uninstalledIds.stream().map(id -> UserAppRole.SUSPENDED_BY_UNINSTALL + id).toList();
      return groupBindingsOf(applicationId, realmRefName, tenantId)
         .filter(Filters.eq("status", UserAppRole.STATUS_SUSPENDED), Filters.in("suspendedBy", markers))
         .update(new UpdateOptions().multi(true),
            UpdateOperators.set("status", UserAppRole.STATUS_ACTIVE),
            UpdateOperators.unset("suspendedBy"),
            UpdateOperators.inc("version", 1))
         .getModifiedCount();
   }

   private dev.morphia.query.Query<UserAppRole> groupBindingsOf(String applicationId, String realmRefName, String tenantId) {
      return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
         .find(UserAppRole.class)
         // One filter per field: two filters on the same field would collapse into one key.
         .filter(Filters.eq("applicationId", applicationId),
            Filters.eq("realmRefName", realmRefName),
            Filters.ne("groupRefName", null),
            Filters.eq("tenantIds", List.of(tenantId)));
   }

   /** One binding per (user, application, realm). */
   public static final String UNIQUE_USER_BINDING_INDEX = "uidx_user_app_role_user";
   /** One binding per (group, application, realm). */
   public static final String UNIQUE_GROUP_BINDING_INDEX = "uidx_user_app_role_group";

   private final AtomicBoolean uniqueIndexesReady = new AtomicBoolean();

   /**
    * The unique indexes behind {@link #requireValid}'s duplicate check, so concurrent writers
    * cannot both pass it: (userId, applicationId, realmRefName) over user bindings and
    * (groupRefName, applicationId, realmRefName) over group bindings.
    */
   public void ensureUniqueIndexes() {
      if (uniqueIndexesReady.get()) {
         return;
      }
      var datastore = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
      String collection = datastore.getMapper().getEntityModel(UserAppRole.class).collectionName();
      var raw = datastore.getDatabase().getCollection(collection);
      try {
         ValidatedWritesOnly.ensureUniqueIndex(raw, UNIQUE_USER_BINDING_INDEX,
            Indexes.ascending("userId", "applicationId", "realmRefName"),
            new org.bson.Document("userId", new org.bson.Document("$type", "string")));
         ValidatedWritesOnly.ensureUniqueIndex(raw, UNIQUE_GROUP_BINDING_INDEX,
            Indexes.ascending("groupRefName", "applicationId", "realmRefName"),
            new org.bson.Document("groupRefName", new org.bson.Document("$type", "string")));
      } catch (MongoException e) {
         throw new IllegalStateException("Cannot create the unique binding indexes on " + collection
            + ": duplicate (subject, application, realm) bindings exist; merge them, then retry", e);
      }
      uniqueIndexesReady.set(true);
   }

   void requireValid(Datastore datastore, UserAppRole binding) {
      Objects.requireNonNull(binding, "binding");
      ensureUniqueIndexes();
      normalize(binding);
      Application application = binding.getApplicationId() == null ? null
         : datastore.find(Application.class).filter(Filters.eq("refName", binding.getApplicationId())).first();
      ApplicationBindingValidator.validate(binding, application, tenant ->
         ApplicationInstallRepo.findActive(datastore, binding.getApplicationId(), binding.getRealmRefName(), tenant)
            .isPresent(), alsoReservedRoles());
      Filter subject = binding.groupBinding()
         ? Filters.eq("groupRefName", binding.getGroupRefName())
         : Filters.eq("userId", binding.getUserId());
      UserAppRole other = datastore.find(UserAppRole.class)
         .filter(subject,
            Filters.eq("applicationId", binding.getApplicationId()),
            Filters.eq("realmRefName", binding.getRealmRefName()))
         .first();
      if (other != null && !Objects.equals(other.getId(), binding.getId())) {
         throw new ApplicationAccessException(Code.BINDING_INVALID, String.format(
            "%s already has binding %s for application '%s' in realm '%s'; update that one",
            binding.groupBinding() ? "group " + binding.getGroupRefName() : binding.getUserId(),
            other.getId(), binding.getApplicationId(), binding.getRealmRefName()));
      }
   }


   // ------------------------------------------------------------------ writes that skip validation

   private static final java.util.Set<String> PERSISTED_FIELDS = ValidatedWritesOnly.persistedFields(UserAppRole.class);

   /** Every persisted field: field-pair and bulk updates cannot bypass {@link #requireValid}. */
   @Override
   protected java.util.Set<String> serverOwnedFields() {
      return PERSISTED_FIELDS;
   }

   @Override
   public UserAppRole merge(UserAppRole entity) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "merge", UserAppRole.class);
   }

   @Override
   public UserAppRole merge(Datastore datastore, UserAppRole entity) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "merge", UserAppRole.class);
   }

   @Override
   public UserAppRole merge(MorphiaSession session, UserAppRole entity) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "merge", UserAppRole.class);
   }

   @Override
   public List<UserAppRole> merge(List<UserAppRole> entities) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "merge", UserAppRole.class);
   }

   @Override
   public List<UserAppRole> merge(Datastore datastore, List<UserAppRole> entities) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "merge", UserAppRole.class);
   }

   @Override
   public List<UserAppRole> merge(MorphiaSession session, List<UserAppRole> entities) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "merge", UserAppRole.class);
   }

   @Override
   public long updateActiveStatus(ObjectId id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "updateActiveStatus", UserAppRole.class);
   }

   @Override
   public long updateActiveStatus(Datastore datastore, ObjectId id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "updateActiveStatus", UserAppRole.class);
   }

   @Override
   public long updateActiveStatus(String id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "updateActiveStatus", UserAppRole.class);
   }

   @Override
   public long updateActiveStatus(String realmId, String id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.BINDING_INVALID, "updateActiveStatus", UserAppRole.class);
   }

   private static void normalize(UserAppRole binding) {
      binding.setUserId(trim(binding.getUserId()));
      binding.setGroupRefName(trim(binding.getGroupRefName()));
      binding.setApplicationId(trim(binding.getApplicationId()));
      binding.setRealmRefName(trim(binding.getRealmRefName()));
      binding.setDefaultTenantId(trim(binding.getDefaultTenantId()));
      if (binding.getRoles() != null) {
         binding.setRoles(new ArrayList<>(binding.getRoles().stream()
            .map(role -> role == null ? null : role.trim()).distinct().toList()));
      }
      if (binding.getTenantIds() != null) {
         binding.setTenantIds(new ArrayList<>(binding.getTenantIds().stream()
            .map(tenant -> tenant == null ? null : tenant.trim()).toList()));
      }
   }

   private static String trim(String value) {
      return value == null || value.isBlank() ? null : value.trim();
   }
}
