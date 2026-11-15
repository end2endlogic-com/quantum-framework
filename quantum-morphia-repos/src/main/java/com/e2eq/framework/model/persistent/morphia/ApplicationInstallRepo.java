package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;
import com.e2eq.framework.model.security.ApplicationInstall;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.mongodb.MongoException;
import com.mongodb.client.model.Indexes;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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

   /** Ids of the uninstalled installs of {@code applicationId} for {@code tenantId} in the realm. */
   public List<ObjectId> findUninstalledIdsWithIgnoreRules(String applicationId, String realmRefName, String tenantId) {
      try (var cursor = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())
            .find(ApplicationInstall.class)
            .filter(Filters.eq("applicationId", applicationId),
               Filters.eq("realmRefName", realmRefName),
               Filters.eq("tenantId", tenantId),
               Filters.eq("status", ApplicationInstall.STATUS_UNINSTALLED))
            .iterator()) {
         return cursor.toList().stream().map(ApplicationInstall::getId).toList();
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

   /** One active install per (application, realm, tenant). */
   public static final String UNIQUE_ACTIVE_INSTALL_INDEX = "uidx_application_install_active";

   private final AtomicBoolean uniqueIndexReady = new AtomicBoolean();

   /**
    * The unique index behind {@link #requireValid}'s second-active-install check, so concurrent
    * installs cannot both pass it: (applicationId, realmRefName, tenantId) over active installs
    * (a partial index on {@code status: active}; uninstalled rows may repeat).
    */
   public void ensureUniqueIndex() {
      if (uniqueIndexReady.get()) {
         return;
      }
      var datastore = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
      String collection = datastore.getMapper().getEntityModel(ApplicationInstall.class).collectionName();
      try {
         ValidatedWritesOnly.ensureUniqueIndex(datastore.getDatabase().getCollection(collection),
            UNIQUE_ACTIVE_INSTALL_INDEX, Indexes.ascending("applicationId", "realmRefName", "tenantId"),
            new org.bson.Document("status", ApplicationInstall.STATUS_ACTIVE));
      } catch (MongoException e) {
         throw new IllegalStateException("Cannot create unique index " + UNIQUE_ACTIVE_INSTALL_INDEX + " on "
            + collection + ": several active installs exist for one (application, realm, tenant); uninstall the "
            + "extra ones, then retry", e);
      }
      uniqueIndexReady.set(true);
   }

   void requireValid(Datastore datastore, ApplicationInstall install) {
      if (install == null) {
         throw new ApplicationAccessException(Code.INSTALL_INVALID, "install is required");
      }
      ensureUniqueIndex();
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


   // ------------------------------------------------------------------ writes that skip validation

   private static final java.util.Set<String> PERSISTED_FIELDS = ValidatedWritesOnly.persistedFields(ApplicationInstall.class);

   /** Every persisted field: field-pair and bulk updates cannot bypass {@link #requireValid}. */
   @Override
   protected java.util.Set<String> serverOwnedFields() {
      return PERSISTED_FIELDS;
   }

   @Override
   public ApplicationInstall merge(ApplicationInstall entity) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "merge", ApplicationInstall.class);
   }

   @Override
   public ApplicationInstall merge(Datastore datastore, ApplicationInstall entity) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "merge", ApplicationInstall.class);
   }

   @Override
   public ApplicationInstall merge(MorphiaSession session, ApplicationInstall entity) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "merge", ApplicationInstall.class);
   }

   @Override
   public List<ApplicationInstall> merge(List<ApplicationInstall> entities) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "merge", ApplicationInstall.class);
   }

   @Override
   public List<ApplicationInstall> merge(Datastore datastore, List<ApplicationInstall> entities) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "merge", ApplicationInstall.class);
   }

   @Override
   public List<ApplicationInstall> merge(MorphiaSession session, List<ApplicationInstall> entities) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "merge", ApplicationInstall.class);
   }

   @Override
   public long updateActiveStatus(ObjectId id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "updateActiveStatus", ApplicationInstall.class);
   }

   @Override
   public long updateActiveStatus(Datastore datastore, ObjectId id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "updateActiveStatus", ApplicationInstall.class);
   }

   @Override
   public long updateActiveStatus(String id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "updateActiveStatus", ApplicationInstall.class);
   }

   @Override
   public long updateActiveStatus(String realmId, String id, ActiveStatus activeStatus) {
      throw ValidatedWritesOnly.refused(Code.INSTALL_INVALID, "updateActiveStatus", ApplicationInstall.class);
   }

   private static boolean blank(String value) {
      return value == null || value.isBlank();
   }
}
