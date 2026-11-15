package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationBindingValidator;
import com.e2eq.framework.model.security.UserAppRole;
import dev.morphia.Datastore;
import dev.morphia.DeleteOptions;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application bindings ({@link UserAppRole}), in the system realm next to credentials and realm
 * assignments. Every save applies {@link ApplicationBindingValidator} against the registered
 * application and its installs, normalizes the row, and refuses a second row for the same
 * (subject, application, realm). Who may write which roles is decided by the caller's service
 * before it saves. {@link #upsertById} writes under a caller-supplied id for migrations.
 */
@ApplicationScoped
public class UserAppRoleRepo extends MorphiaRepo<UserAppRole> {

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
    * Removes the group bindings of an application that grant only {@code tenantId} in the realm:
    * what uninstalling the application for that tenant takes with it. Groups never span tenants.
    */
   public long deleteGroupBindingsForTenant(String applicationId, String realmRefName, String tenantId) {
      return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
         .find(UserAppRole.class)
         .filter(Filters.eq("applicationId", applicationId),
            Filters.eq("realmRefName", realmRefName),
            Filters.exists("groupRefName"),
            Filters.ne("groupRefName", null),
            Filters.eq("tenantIds", tenantId),
            Filters.size("tenantIds", 1))
         .delete(new DeleteOptions().multi(true))
         .getDeletedCount();
   }

   void requireValid(Datastore datastore, UserAppRole binding) {
      Objects.requireNonNull(binding, "binding");
      normalize(binding);
      Application application = binding.getApplicationId() == null ? null
         : datastore.find(Application.class).filter(Filters.eq("refName", binding.getApplicationId())).first();
      ApplicationBindingValidator.validate(binding, application, tenant ->
         ApplicationInstallRepo.findActive(datastore, binding.getApplicationId(), binding.getRealmRefName(), tenant)
            .isPresent());
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
