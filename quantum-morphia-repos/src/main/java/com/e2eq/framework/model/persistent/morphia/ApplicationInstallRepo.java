package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationInstall;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application installs, in the system realm next to the application registry. Every save checks
 * the install's shape, that the application is registered, and that no other active install
 * exists for the same (application, realm, tenant). {@link #upsertById} writes an install under a
 * caller-supplied id, so a migration can keep the ids of the records it replaces and re-running it
 * changes nothing.
 */
@ApplicationScoped
public class ApplicationInstallRepo extends MorphiaRepo<ApplicationInstall> {

   @Override
   public String getSecurityContextRealmId() {
      return envConfigUtils.getSystemRealm();
   }

   @Override
   public ApplicationInstall save(Datastore datastore, ApplicationInstall value) {
      requireValid(datastore, value);
      return super.save(datastore, value);
   }

   @Override
   public ApplicationInstall save(MorphiaSession session, ApplicationInstall value) {
      requireValid(session, value);
      return super.save(session, value);
   }

   @Override
   public List<ApplicationInstall> save(Datastore datastore, List<ApplicationInstall> entities) {
      entities.forEach(entity -> requireValid(datastore, entity));
      return super.save(datastore, entities);
   }

   @Override
   public List<ApplicationInstall> save(MorphiaSession session, List<ApplicationInstall> entities) {
      entities.forEach(entity -> requireValid(session, entity));
      return super.save(session, entities);
   }

   /**
    * Inserts the install under its id, or replaces the stored row with that id. The id is
    * required. Idempotent: writing the same install twice leaves one row.
    */
   public ApplicationInstall upsertById(ApplicationInstall install) {
      Objects.requireNonNull(install, "install");
      if (install.getId() == null) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID, "an upsert needs the install's id");
      }
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
      ApplicationInstall stored = findByIdWithIgnoreRules(datastore, install.getId());
      install.setVersion(stored == null ? null : stored.getVersion());
      return save(datastore, install);
   }

   /** The active install of {@code applicationId} for {@code tenantId} in {@code realmRefName}. */
   public Optional<ApplicationInstall> findActiveWithIgnoreRules(String applicationId, String realmRefName, String tenantId) {
      return findActive(morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()),
         applicationId, realmRefName, tenantId);
   }

   public boolean isInstalled(String applicationId, String realmRefName, String tenantId) {
      return findActiveWithIgnoreRules(applicationId, realmRefName, tenantId).isPresent();
   }

   /** Every install in a realm, whatever its status. */
   public List<ApplicationInstall> findByRealmWithIgnoreRules(String realmRefName) {
      try (var cursor = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
            .find(ApplicationInstall.class)
            .filter(Filters.eq("realmRefName", realmRefName))
            .iterator()) {
         return cursor.toList();
      }
   }

   public Optional<ApplicationInstall> findByIdWithIgnoreRules(ObjectId id) {
      return Optional.ofNullable(findByIdWithIgnoreRules(
         morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId()), id));
   }

   static Optional<ApplicationInstall> findActive(Datastore datastore, String applicationId, String realmRefName,
                                                  String tenantId) {
      if (applicationId == null || realmRefName == null || tenantId == null) {
         return Optional.empty();
      }
      return Optional.ofNullable(datastore.find(ApplicationInstall.class)
         .filter(Filters.eq("applicationId", applicationId.trim()),
            Filters.eq("realmRefName", realmRefName.trim()),
            Filters.eq("tenantId", tenantId.trim()),
            Filters.eq("status", ApplicationInstall.STATUS_ACTIVE))
         .first());
   }

   private static ApplicationInstall findByIdWithIgnoreRules(Datastore datastore, ObjectId id) {
      return datastore.find(ApplicationInstall.class).filter(Filters.eq("_id", id)).first();
   }

   void requireValid(Datastore datastore, ApplicationInstall install) {
      if (install == null) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID, "install is required");
      }
      if (blank(install.getTenantId()) || blank(install.getApplicationId()) || blank(install.getRealmRefName())) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID, "tenantId, applicationId and realmRefName are required");
      }
      if (install.getStatus() == null || !ApplicationInstall.STATUS_VALUES.contains(install.getStatus())) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID,
            "status '" + install.getStatus() + "' is not one of " + ApplicationInstall.STATUS_VALUES);
      }
      install.setTenantId(install.getTenantId().trim());
      install.setApplicationId(install.getApplicationId().trim());
      install.setRealmRefName(install.getRealmRefName().trim());
      if (datastore.find(Application.class).filter(Filters.eq("refName", install.getApplicationId())).first() == null) {
         throw new ApplicationAccessException(Code.APPLICATION_UNKNOWN,
            "application '" + install.getApplicationId() + "' is not registered");
      }
      if (install.active()) {
         findActive(datastore, install.getApplicationId(), install.getRealmRefName(), install.getTenantId())
            .filter(other -> !Objects.equals(other.getId(), install.getId()))
            .ifPresent(other -> {
               throw new ApplicationAccessException(Code.INSTALL_INVALID, String.format(
                  "application '%s' is already installed for tenant '%s' in realm '%s' (install %s)",
                  install.getApplicationId(), install.getTenantId(), install.getRealmRefName(), other.getId()));
            });
      }
   }

   private static boolean blank(String value) {
      return value == null || value.isBlank();
   }
}
