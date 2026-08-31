package com.e2eq.framework.model.persistent.migration.base;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.SecurityUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import dev.morphia.Datastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.semver4j.Semver;



import java.util.*;
import java.util.ArrayList;


@ApplicationScoped
public class MigrationService {

   @ConfigProperty(name = "quantum.database.version")
   protected String targetDatabaseVersion;

   @ConfigProperty(name = "quantum.database.scope")
   protected String databaseScope;
   @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "mycompanyxyz-com")
   protected String defaultRealm;
   @ConfigProperty(name = "quantum.realmConfig.testRealm", defaultValue = "test-system-com")
   protected String testRealm;
   /** System realm database name; must match quantum.realmConfig.systemRealm in application.properties (e.g. system-com). */
   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com")
   protected String systemRealm;

   @ConfigProperty(name = "quantum.database.migration.enabled")
   protected boolean enabled;

   @ConfigProperty(name = "quantum.migration.application-id")
   Optional<String> migrationApplicationId;

   /** Mirrors RuleContext's fail-closed switch; when true, migrations must carry an application id. */
   @ConfigProperty(name = "quantum.security.policy-scope.require-application", defaultValue = "false")
   boolean requireApplicationScope;

   /** Shared database used only for short-lived migration/seed coordination locks. */
   @ConfigProperty(name = "quantum.database.coordination-database", defaultValue = "quantum-coordination-shared")
   String coordinationDatabase;

   /**
    * Optional comma-separated list of additional realms to initialize on startup before seed packs run.
    * When unset, startup migration falls back to the historical behavior:
    * always evaluate the system realm and evaluate the default realm when it exists.
    *
    * Example: "system-com,mycompany-com,test-com"
    */
   @ConfigProperty(name = "quantum.migration.apply.realms")
   Optional<String> startupRealmsCsv;

   /**
    * When true, startup realm resolution is restricted to the cross-tenant SYSTEM realm only:
    * the tenant-shaped TEST realm and the DEFAULT realm are dropped from the scan (the default
    * realm is kept only when it equals the system realm). An explicit
    * {@code quantum.migration.apply.realms} entry is still honored, so a caller can opt a specific
    * realm back in. Reuses the existing {@code quantum.realm.seed-system-only} flag so a
    * "system-only" database neither creates nor even scans the test realm. Defaults to false so
    * existing multi-tenant/dev behavior is unchanged (non-breaking).
    */
   @ConfigProperty(name = "quantum.realm.seed-system-only", defaultValue = "false")
   boolean seedSystemOnly;

   @Inject
   DatabaseVersionRepo databaseVersionRepo;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   BeanManager beanManager;

   @Inject
   ChangeSetRecordRepo changesetRecordRepo;

   @Inject
   MongoClient mongoClient;

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   public void initializeStartupRealms() {
      initializeStartupRealms(true);
   }

   /**
    * Run startup migrations, optionally excluding the system realm. In remote
    * mode (control-plane split, {@code quantum.mode=remote}) the control plane
    * owns the system realm's lifecycle, so the caller (FrameworkStartupCoordinator)
    * passes {@code includeSystemRealm=false}; app-realm migrations still run
    * locally. This class stays mode-unaware — the deployment mode is resolved
    * above it.
    */
   public void initializeStartupRealms(boolean includeSystemRealm) {
      if (!enabled) {
         Log.warn("!!!! >>>> Database migration is disabled by configuration (quantum.database.migration.enabled=false)");
         return;
      } else {
         Log.info(">> Migration Service enabled");
      }

      List<String> databaseNames = mongoClient.listDatabaseNames().into(new ArrayList<>());
      List<String> startupRealms = resolveStartupRealms(databaseNames, includeSystemRealm);
      Log.infof(">> Migration startup realms: %s", startupRealms);
      for (String realm : startupRealms) {
         ensureRealmInitializedOnStartup(realm, databaseNames.contains(realm));
      }
   }

   List<String> resolveStartupRealms(List<String> existingDatabaseNames) {
      return resolveStartupRealms(existingDatabaseNames, true);
   }

   public List<String> resolveStartupRealms(List<String> existingDatabaseNames, boolean includeSystemRealm) {
      LinkedHashSet<String> realms = new LinkedHashSet<>();

      if (includeSystemRealm) {
         realms.add(systemRealm);
      }
      // System-only mode: do NOT auto-include the default (or test) realm in the scan. The
      // default realm is kept only when it equals the system realm. Explicit apply.realms
      // entries below are still honored so a caller can opt a specific realm back in.
      if (!seedSystemOnly && existingDatabaseNames.contains(defaultRealm)) {
         realms.add(defaultRealm);
      }

      if (startupRealmsCsv.isPresent()) {
         String csv = startupRealmsCsv.get().trim();
         if (!csv.isEmpty() && !csv.equalsIgnoreCase("none")) {
            for (String part : csv.split(",")) {
               String realm = part.trim();
               if (!realm.isEmpty()) {
                  realms.add(realm);
               }
            }
         }
      }

      if (!includeSystemRealm && realms.remove(systemRealm)) {
         Log.warnf(">> Migration: system realm %s excluded from startup realms (it was listed in "
             + "quantum.migration.apply.realms, but this deployment does not own the system realm)", systemRealm);
      }

      return new ArrayList<>(realms);
   }

   private void ensureRealmInitializedOnStartup(String realm, boolean realmExists) {
      if (!realmExists) {
         Log.warnf("    ### Realm %s does not exist, creating it", realm);
         migrateRealmSynchronously(realm);
         return;
      }

      Log.infof("### Realm %s exists, checking if it is initialized", realm);
      try {
         checkInitialized(realm);
      } catch (DatabaseMigrationException e) {
         Log.warnf("    ### Realm %s is not initialized or is out of date (%s), initializing it", realm, e.getMessage());
         migrateRealmSynchronously(realm);
      }
   }

   private void migrateRealmSynchronously(String realm) {
      MultiEmitter<String> emitter = newLoggingEmitter();
      String applicationId = migrationApplicationId
         .map(String::trim)
         .filter(value -> !value.isEmpty())
         .orElse(null);

      if (applicationId == null) {
         // Application-scoped policy evaluation is opt-in (quantum.security.policy-scope.require-application).
         // When it is off, policies are still evaluated unscoped, so an unset migration application id is
         // legal: fall back to the historical system contexts rather than failing startup. When it is on,
         // RuleContext would fail closed anyway, so fail here with the actionable message.
         if (requireApplicationScope) {
            throw new IllegalStateException(
               "quantum.migration.application-id is required for startup migrations when "
                  + "quantum.security.policy-scope.require-application=true");
         }
         runStartupMigrationRulesOff(
            securityUtils.getSystemPrincipalContext(),
            securityUtils.getSystemSecurityResourceContext(),
            realm,
            emitter);
         return;
      }

      var systemPrincipal = securityUtils.getSystemPrincipalContext();
      var migrationPrincipal = new com.e2eq.framework.model.securityrules.PrincipalContext.Builder()
         .withDefaultRealm(systemPrincipal.getDefaultRealm())
         .withApplicationId(applicationId)
         .withDomainContext(systemPrincipal.getDomainContext())
         .withDataDomain(systemPrincipal.getDataDomain())
         .withUserId(systemPrincipal.getUserId())
         .withRoles(systemPrincipal.getRoles())
         .withScope(systemPrincipal.getScope())
         .build();
      var migrationResource = new com.e2eq.framework.model.securityrules.ResourceContext.Builder()
         .withRealm(realm)
         .withApplicationId(applicationId)
         .withArea("MIGRATION")
         .withFunctionalDomain("DATABASE")
         .withAction("APPLY")
         .build();
      runStartupMigrationRulesOff(migrationPrincipal, migrationResource, realm, emitter);
   }

   /**
    * Run startup migration for one realm with rule evaluation switched off.
    *
    * <p>Startup migration cannot evaluate policy, and the reason is structural rather than
    * incidental. Applying a changeset saves bookkeeping records through {@code MorphiaRepo},
    * which resolves field-level policy, which asks {@code RuleContext}. Where the deployment
    * delegates authorization to the central auth service, that provider requires a
    * <em>request-scoped</em> bearer token — and a {@code StartupEvent} has no request. The
    * result was a {@code SecurityException} thrown several frames deep in a Morphia save,
    * before the HTTP port ever opened.</p>
    *
    * <p>Supplying the system identity alone is not enough: it establishes <em>who</em> the
    * caller is, while the delegating provider still tries to go and ask about them. The
    * ignore-rules scope is the declaration this codebase already provides for exactly this
    * case — {@code RepoSecurityFilterBuilder.buildExcludedFieldPaths} tests it first and
    * short-circuits, and its own diagnostic points here: <em>"Use an explicit ignore-rules
    * scope only for authorized internal reads."</em></p>
    *
    * <p>The system identity is still established inside the scope. Rules-off is not
    * anonymity: the migration records remain stamped with the system principal and its data
    * domain, so what was written at startup, and under whose identity, stays attributable.
    * The scope is depth-counted and closed in a finally block by try-with-resources, so
    * nested work cannot leave the thread elevated after migration returns.</p>
    *
    * <p>This is deliberately all startup migration rather than only the bookkeeping saves:
    * a migration that could half-apply — some statements rules-off, others fail-closed
    * against an unreachable auth service — is a worse outcome than one that is uniformly
    * privileged and uniformly attributable.</p>
    */
   private void runStartupMigrationRulesOff(
      com.e2eq.framework.model.securityrules.PrincipalContext principal,
      com.e2eq.framework.model.securityrules.ResourceContext resource,
      String realm,
      MultiEmitter<String> emitter) {
      try (SecurityCallScope.Scope ignoredRules = SecurityCallScope.openIgnoringRules()) {
         SecurityCallScope.runWithContexts(
            principal,
            resource,
            () -> {
               runAllUnRunMigrations(realm, emitter);
               applyAllIndexes(realm);
            }
         );
      }
   }

   @SuppressWarnings("unchecked")
   private MultiEmitter<String> newLoggingEmitter() {
      return (MultiEmitter<String>) java.lang.reflect.Proxy.newProxyInstance(
         MultiEmitter.class.getClassLoader(),
         new Class[]{MultiEmitter.class},
         (proxy, method, args) -> {
            String name = method.getName();
            if ("emit".equals(name) && args != null && args.length == 1 && args[0] instanceof String value) {
               Log.info(value);
            } else if ("fail".equals(name) && args != null && args.length == 1 && args[0] instanceof Throwable failure) {
               Log.error("Migration emitter failure", failure);
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == Void.TYPE) return null;
            if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
            if (returnType == long.class || returnType == Long.class) return 0L;
            if (MultiEmitter.class.isAssignableFrom(returnType)) return proxy;
            return null;
         }
      );
   }


   public void checkInitialized (String realm) {
      Optional<DatabaseVersion> oDatabaseVersion = databaseVersionRepo.findCurrentVersion(realm);
      if (!oDatabaseVersion.isPresent()) {
         throw new DatabaseMigrationException(String.format("Database Version was not found, for realm: %s. Please initialize / run migrations on the database", realm));
      }
      DatabaseVersion currentVersion = oDatabaseVersion.get();
      Semver currentSemVersion = currentVersion.getCurrentSemVersion();
      Semver requiredSemVersion = Semver.parse(targetDatabaseVersion);
      if (currentSemVersion.compareTo(requiredSemVersion) < 0) {
         throw new DatabaseMigrationException(realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      }

      // Check for pending changesets with mismatched checksums or version bumps
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
      List<ChangeSetBean> allChangeSets = getAllChangeSetBeans();
      for (ChangeSetBean chb : allChangeSets) {
         // Only check changesets applicable to this realm
         Set<String> applicable = chb.getApplicableDatabases();
         if (applicable != null && !applicable.isEmpty() && !applicable.contains(realm)) {
            continue;
         }
         Optional<ChangeSetRecord> record = changesetRecordRepo.findLatestByChangeSetName(datastore, chb.getName());
         if (record.isPresent()) {
            if (chb.getChecksum() != null && !chb.getChecksum().equals(record.get().getChecksum())) {
               throw new DatabaseMigrationException(String.format("Database %s has changeset %s with mismatched checksum. Please run migrations.", realm, chb.getName()));
            }
            if (record.get().getChangeSetVersion() < chb.getChangeSetVersion()) {
               throw new DatabaseMigrationException(String.format("Database %s has changeset %s with outdated version (recorded=%d, current=%d). Please run migrations.",
                       realm, chb.getName(), record.get().getChangeSetVersion(), chb.getChangeSetVersion()));
            }
         }
         // If no record exists, skip silently — the changeset may be new and not yet
         // applicable, or the DB was initialized before this changeset was added.
         // runAllUnRunMigrations() handles unrecorded changesets correctly.
      }

      if (currentSemVersion.compareTo(requiredSemVersion) > 0) {
         Log.warnf("Database %s version is higher than required. Current version: %s, required version: %s", realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      } else {
         Log.infof("Database %s version is up to date. Current version: %s, required version: %s — all changesets current", realm, currentVersion.getCurrentVersionString(), targetDatabaseVersion);
      }
   }

   public void dropIndexOnCollection (String realm, String collectionName) {
      requireRealmOwnedByThisMigrationService(realm, "drop indexes");
      mongoClient.getDatabase(realm).getCollection(collectionName).dropIndexes();
   }

   public void applyIndexes (String realmId) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      requireRealmOwnedByThisMigrationService(realmId, "apply indexes");
      morphiaDataStoreWrapper.getDataStore(realmId).applyIndexes();
   }

   public void applyIndexes (String realmId, String collection) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      requireRealmOwnedByThisMigrationService(realmId, "apply indexes");
      Optional<EntityModel> em = morphiaDataStoreWrapper.getDataStore(realmId).getMapper().getMappedEntities().stream().filter(entity -> entity.collectionName().equals(collection)).findFirst();
      if (!em.isPresent()) {
         throw new NotFoundException(String.format("Collection %s not found in realm %s", collection, realmId));
      }
      morphiaDataStoreWrapper.getDataStore(realmId).ensureIndexes(em.get().getType());
   }

   /**
    * Ensures all Morphia-mapped entity collections exist in the realm database by applying indexes.
    * This creates any missing collections (MongoDB creates a collection when the first index is created).
    * Called after migrations so the system realm has all expected collections (e.g. credentialUserIdPassword, userProfile, policy, realm).
    */
   public void applyAllIndexes(String realmId) {
      Objects.requireNonNull(realmId);
      requireRealmOwnedByThisMigrationService(realmId, "apply all indexes");
      var datastore = morphiaDataStoreWrapper.getDataStore(realmId);
      var entities = datastore.getMapper().getMappedEntities();
      Log.infof("applyAllIndexes: ensuring indexes for %d mapped entity types in realm %s", entities.size(), realmId);
      for (var entity : entities) {
         try {
            datastore.ensureIndexes(entity.getType());
            Log.debugf("applyAllIndexes: ensured indexes for %s in %s", entity.collectionName(), realmId);
         } catch (Exception e) {
            Log.warnf(e, "applyAllIndexes: failed to ensure indexes for %s in %s", entity.collectionName(), realmId);
         }
      }
      // Each mapped entity was handled above. Do not invoke Datastore.applyIndexes() as a
      // second global pass: it repeats every operation and turns an intentionally isolated,
      // logged legacy-index conflict into a fatal startup exception.
      Log.infof("applyAllIndexes: completed best-effort index reconciliation for realm %s", realmId);
   }

   public void dropAllIndexes (String realmId) {
      Objects.requireNonNull(realmId, "RealmId cannot be null");
      requireRealmOwnedByThisMigrationService(realmId, "drop all indexes");
      morphiaDataStoreWrapper.getDataStore(realmId).getMapper().getMappedEntities().forEach(entity -> {
         mongoClient.getDatabase(realmId).getCollection(entity.collectionName()).dropIndexes();
      });
   }


   public void checkMigrationRequired () {
      checkInitialized(defaultRealm);
      checkInitialized(systemRealm);
      checkInitialized(testRealm);
   }

   protected void log (String message, MultiEmitter<? super String> emitter) {
      Log.info(message);
      emitter.emit(message);
   }


   public void checkDataBaseVersion () {
      Log.info("MigrationService check is enabled");
      Log.infof("MigrationService targetDatabaseVersion: %s", targetDatabaseVersion);
      Log.infof("MigrationService databaseScope: %s", databaseScope);
      DistributedLock lock = getMigrationLock(systemRealm);
      lock.acquire();
      try {
         migrationRequired(morphiaDataStoreWrapper.getDataStore(defaultRealm), targetDatabaseVersion);
         migrationRequired(morphiaDataStoreWrapper.getDataStore(systemRealm), targetDatabaseVersion);
         migrationRequired(morphiaDataStoreWrapper.getDataStore(testRealm), targetDatabaseVersion);
      } finally {
         lock.release();
      }
   }

   protected DistributedLock getMigrationLock (String realm) {
      MongoCollection<Document> collection = mongoClient
                                                .getDatabase(coordinationDatabase)
                                                .getCollection("locks");
      Sherlock sherlock = MongoSherlock.create(collection);
      // create Lock name

      DistributedLock lock = sherlock.createLock(String.format("migration-lock-%s", realm));
      return lock;
   }

   public Optional<DatabaseVersion> getCurrentDatabaseVersion (String realm) {
      return getCurrentDatabaseVersion(morphiaDataStoreWrapper.getDataStore(realm));
   }

   public Optional<DatabaseVersion> getCurrentDatabaseVersion (Datastore datastore) {
      // Find the current version of the database.
      return databaseVersionRepo.findCurrentVersion(datastore);
   }

   public void migrationRequired (Datastore datastore, String requiredVersion) {

      Semver requiredSemver = Semver.parse(requiredVersion);
      if (requiredSemver == null) {
         throw new IllegalArgumentException(String.format(" realm: %s ,The current version string: %s is not parsable, check semver4j for more details about string format", datastore.getDatabase().getName(), requiredVersion));
      }

      Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findCurrentVersion(datastore);
      if (odbVersion.isPresent()) {
         Log.infof("DBVersion: %s", odbVersion.get().toString());
         if (odbVersion.get().getCurrentSemVersion().isLowerThan(requiredVersion)) {
            throw new DatabaseMigrationException(datastore.getDatabase().getName(), odbVersion.get().toString(), requiredVersion);
         }

      } else
         throw new IllegalStateException(String.format("Empty database version collection found for  dataStore:%s, dataStore needs to be initialized", datastore.getDatabase().getName()));
   }

   public DatabaseVersion saveDatabaseVersion (Datastore datastore, String versionString) {
      if (versionString == null || versionString.isEmpty()) {
         throw new IllegalArgumentException("versionString cannot be null or empty");
      }


      Optional<DatabaseVersion> odbVersion = databaseVersionRepo.findByRefName(datastore, datastore.getDatabase().getName());
      DatabaseVersion dbVersion;

      if (odbVersion.isPresent()) {
         dbVersion = odbVersion.get();
         Log.infof("DBVersion: %s", dbVersion.toString());
         dbVersion.setCurrentVersionString(versionString);
         dbVersion.setLastUpdated(new java.util.Date());
      } else {
         dbVersion = new DatabaseVersion();
         dbVersion.setCurrentVersionString(versionString);
         dbVersion.setLastUpdated(new java.util.Date());
         dbVersion.setRefName(datastore.getDatabase().getName());
      }

      return databaseVersionRepo.save(datastore, dbVersion);
   }

   public List<ChangeSetBean> getAllChangeSetBeans () {
      if (Log.isDebugEnabled())
       Log.debug("== Finding changeSetBeans ===============");
      else {
         Log.info("Scanning for changeSet Beans - should only be seen on startup");
      }
      List<ChangeSetBean> changeSetBeans = new ArrayList<>();
      Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
      for (Bean bean : changeSets) {
         CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
         ChangeSetBean chb = (ChangeSetBean) beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
         if (Log.isDebugEnabled()) {}
            Log.debugf("Found ChangeSetBean: %s", chb.getName());
         changeSetBeans.add(chb);
      }

      // sort the change set beans in order of from / to and priority
      changeSetBeans.sort(Comparator.comparing(ChangeSetBean::getDbToVersionInt)
                             .thenComparing(ChangeSetBean::getPriority));

      return changeSetBeans;
   }

   public List<ChangeSetBean> getAllPendingChangeSetBeans (String realm, MultiEmitter<? super String> emitter) {
      Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
      Optional<DatabaseVersion> oCurrentDbVersion = this.getCurrentDatabaseVersion(datastore);
      DatabaseVersion currentDbVersion;
      if (!oCurrentDbVersion.isPresent()) {
         log(String.format("No database version found in the database for realm: %s, assuming 1.0.0", realm), emitter);
         currentDbVersion = new DatabaseVersion();
         currentDbVersion.setCurrentVersionString("1.0.0");
         currentDbVersion = getDatabaseVersionRepo().save(realm, currentDbVersion);
      } else {
         currentDbVersion = oCurrentDbVersion.get();
         log(String.format("Current database version: %s", currentDbVersion.getCurrentVersionString()), emitter);
      }


      Semver currentSemDatabaseVersion = currentDbVersion.getCurrentSemVersion();
      List<ChangeSetBean> changeSets = getAllChangeSetBeans();
      log(String.format("Found %d Change Sets:", changeSets.size()), emitter);
      if (changeSets.isEmpty()) {
         log("No Change Sets found", emitter);
      }
      log(String.format("Found %d Change Sets:", changeSets.size()), emitter);
      changeSets.forEach(changeSetBean -> {
         log(String.format("    Change Set: %s", changeSetBean.getName()), emitter);
      });
      List<ChangeSetBean> pendingChangeSetBeans = new ArrayList<>();

      for (ChangeSetBean chb : changeSets) {
         Semver toVersion = new Semver(chb.getDbToVersion());
         Semver currentVersion = new Semver(currentSemDatabaseVersion.getVersion());

         Optional<ChangeSetRecord> record = changesetRecordRepo.findLatestByChangeSetName(datastore, chb.getName());
         boolean shouldRun = !record.isPresent() ||
                             record.get().getChangeSetVersion() < chb.getChangeSetVersion() ||
                             (chb.getChecksum() != null && !chb.getChecksum().equals(record.get().getChecksum()));

         if (toVersion.isGreaterThanOrEqualTo(currentVersion)) {
            log(String.format("ToVersion:%s, is greater than or equal to current version:%s", toVersion.getVersion(), currentVersion.getVersion()), emitter);
            if (shouldRun) {
               String reason = !record.isPresent() ? "no record" :
                              (record.get().getChangeSetVersion() < chb.getChangeSetVersion() ? "version changed" : "checksum changed");
               log(String.format(">> Adding Change Set: %s (v%d) in realm %s - reason: %s", chb.getName(), chb.getChangeSetVersion(), datastore.getDatabase().getName(), reason), emitter);
               pendingChangeSetBeans.add(chb);
            } else {
               log(String.format(">> Already executed change set: %s (latest applied v%d) in realm %s on %tc ", chb.getName(), record.get().getChangeSetVersion(), datastore.getDatabase().getName(), record.get().getLastExecutedDate()), emitter);
            }
         } else if (shouldRun) {
            log(String.format(">> Adding Change Set: %s (v%d) in realm %s - reason: checksum changed (older version) or change record not found", chb.getName(), chb.getChangeSetVersion(), datastore.getDatabase().getName()), emitter);
            pendingChangeSetBeans.add(chb);
         } else {
            log(String.format(">> Skipping Change Set:%s (already applied, toVersion:%s < currentVersion:%s)", chb.getName(), toVersion.getVersion(), currentSemDatabaseVersion), emitter);
         }
      }
      return pendingChangeSetBeans;
   }

   public DatabaseVersionRepo getDatabaseVersionRepo () {
      return databaseVersionRepo;
   }


   public void updateChangeLog (MorphiaSession ds, String realm, ChangeSetBean changeSetBean, MultiEmitter<? super String> emitter) {
      // Upsert ChangeSetRecord by changeSetName to avoid duplicate-key on older deployments with a unique index on name only
      Optional<ChangeSetRecord> existing = changesetRecordRepo.findLatestByChangeSetName(ds, changeSetBean.getName());
      ChangeSetRecord record;
      if (existing.isPresent()) {
         record = existing.get();
      } else {
         record = new ChangeSetRecord();
      }
      // Populate/overwrite all fields
      record.setRealm(realm);
      record.setRefName(changeSetBean.getName());
      record.setAuthor(changeSetBean.getAuthor());
      record.setChangeSetName(changeSetBean.getName());
      record.setDescription(changeSetBean.getDescription());
      record.setPriority(changeSetBean.getPriority());
      record.setDbFromVersion(changeSetBean.getDbFromVersion());
      record.setDbFromVersionInt(changeSetBean.getDbFromVersionInt());
      record.setDbToVersion(changeSetBean.getDbToVersion());
      record.setDbToVersionInt(changeSetBean.getDbToVersionInt());
      record.setChangeSetVersion(changeSetBean.getChangeSetVersion());
      record.setChecksum(changeSetBean.getChecksum());
      record.setLastExecutedDate(new Date());
      record.setScope(changeSetBean.getScope());
      record.setSuccessful(true);

      changesetRecordRepo.save(ds, record);

      DatabaseVersion databaseVersion;
      Optional<DatabaseVersion> oversion = databaseVersionRepo.findCurrentVersion(ds);
      if (!oversion.isPresent()) {
         Log.warnf("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm);
         emitter.emit(String.format("        No database databaseVersion found in the database for realm: %s, assuming 1.0.0", realm));
         databaseVersion = new DatabaseVersion();
         databaseVersion.setRefName(realm);
         databaseVersion.setCurrentVersionString(record.getDbToVersion());
         databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
      } else {
         databaseVersion = oversion.get();
         if (databaseVersion.getCurrentSemVersion().isLowerThan(record.getDbToVersion())) {
            databaseVersion.setCurrentVersionString(record.getDbToVersion());
            databaseVersion = databaseVersionRepo.save(ds, databaseVersion);
         }
      }
   }

   public void runChangeSetBean(String changeSetName, String realm, MultiEmitter<? super String> emitter) throws Exception {
      requireRealmOwnedByThisMigrationService(realm, "run change set");

      List<ChangeSetBean> changeSets = getAllChangeSetBeans();
      Optional<ChangeSetBean> changeSetBeanOptional = changeSets.stream().filter(changeSetBean -> changeSetBean.getName().equals(changeSetName)).findFirst();
      if (!changeSetBeanOptional.isPresent()) {
         throw new NotFoundException(String.format("Change Set Bean:%s not found", changeSetName));
      }
      ChangeSetBean changeSetBean = changeSetBeanOptional.get();

      // get a lock first:
      DistributedLock lock = getMigrationLock(realm);
      log(String.format("-- Got Migration Lock Executing change sets on database / realm:%s --", realm), emitter);
      lock.runLocked(() -> {
         MorphiaSession ds = morphiaDataStoreWrapper.getDataStore(realm).startSession();
         try {
            log(String.format("        Executing Change Set: %s in realm %s", changeSetBean.getName(), realm), emitter);
            emitter.emit(String.format("        Executing Change Set: %s in realm %s", changeSetBean.getName(), realm));
            try {
               changeSetBean.execute(ds, mongoClient, emitter);
            } catch (Exception e) {
               throw new RuntimeException(e);
            }
            log(String.format("        Executed Change Set: %s in realm %s", changeSetBean.getName(), realm), emitter);
            emitter.emit(String.format("        Executed Change Set: %s in realm %s", changeSetBean.getName(), realm));
            updateChangeLog(ds, realm, changeSetBean, emitter);
         } finally {
            ds.close();
         }
      });
   }

   public void runAllUnRunMigrations (String realm, MultiEmitter<? super String> emitter) {
      requireRealmOwnedByThisMigrationService(realm, "run migrations");

      Log.info("-- Running all migrations for realm: " + realm);
      List<ChangeSetBean> changeSetList = getAllPendingChangeSetBeans(realm, emitter);
      if (!changeSetList.isEmpty()) {
         Log.infof("-------------- Migration Starting for: %s--------------", realm);
         emitter.emit(String.format("-------------- Migration Starting for: %s--------------", realm));
         // for each now execute the change set bean
         Log.infof("-- Executing %d change sets --", changeSetList.size());
         emitter.emit(String.format("-- Executing %d change sets --", changeSetList.size()));

         // get a lock first:
         DistributedLock lock = getMigrationLock(realm);
         log(String.format("-- Got Migration Lock Executing change sets on database / realm:%s --", realm), emitter);
         lock.runLocked(() -> {
            changeSetList.forEach(changeSetBean -> {
               log(String.format("    checking for previous execution of Change Set: %s, in database %s", changeSetBean.getName(), realm ), emitter);
               // first check if this change set has run already or not
               Optional<ChangeSetRecord> changeSetRec = changesetRecordRepo.findLatestByChangeSetName(morphiaDataStoreWrapper.getDataStore(realm), changeSetBean.getName());
               boolean shouldRun = !changeSetRec.isPresent() ||
                                   changeSetRec.get().getChangeSetVersion() < changeSetBean.getChangeSetVersion() ||
                                   (changeSetBean.getChecksum() != null && !changeSetBean.getChecksum().equals(changeSetRec.get().getChecksum()));
               if (changeSetRec.isPresent()) {
                  log(String.format("    Found existing record for %s: version=%d, checksum=%s; Bean: version=%d, checksum=%s",
                     changeSetBean.getName(), changeSetRec.get().getChangeSetVersion(), changeSetRec.get().getChecksum(),
                     changeSetBean.getChangeSetVersion(), changeSetBean.getChecksum()), emitter);
               }
               if (shouldRun) {

                  if ((changeSetBean.getApplicableDatabases() == null) ||
                         (changeSetBean.getApplicableDatabases() != null && changeSetBean.getApplicableDatabases().contains(realm))
                  ) {
                     Log.infof("Executing Change Set:%s", changeSetBean.getName());
                     emitter.emit(String.format("Executing Change Set:%s", changeSetBean.getName()));

                     if (changeSetBean.isOverrideDatabase() && changeSetBean.getOverrideDatabaseName() != null &&
                            !changeSetBean.getOverrideDatabaseName().isEmpty() && !changeSetBean.getOverrideDatabaseName().equalsIgnoreCase(realm)) {
                        log(String.format("Overridden Database for changeSetBean:%s, to database:%s from default:%s", changeSetBean.getName(), changeSetBean.getOverrideDatabaseName(), realm), emitter);
                        MorphiaSession ods = morphiaDataStoreWrapper.getDataStore(changeSetBean.getOverrideDatabaseName()).startSession();

                        DistributedLock olock = getMigrationLock(changeSetBean.getOverrideDatabaseName());
                        olock.runLocked(() -> {
                           try {
                              Log.infof("        Starting Transaction for Change Set:%s on database: %s", changeSetBean.getName(), changeSetBean.getOverrideDatabaseName());
                              emitter.emit(String.format("        Starting Transaction for Change Set:%s", changeSetBean.getName()));

                              ods.startTransaction();
                              changeSetBean.execute(ods, mongoClient, emitter);
                              updateChangeLog(ods, changeSetBean.getOverrideDatabaseName(), changeSetBean, emitter);
                              ods.commitTransaction();
                              Log.infof("        Committed Transaction for Change Set:%s", changeSetBean.getName());
                              emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));
                           } catch (Throwable e) {
                              emitter.fail(e);
                              e.printStackTrace();
                              ods.abortTransaction();
                              throw new RuntimeException(e);
                           } finally {
                              ods.close();
                           }
                        });
                     } else {
                        MorphiaSession ds = morphiaDataStoreWrapper.getDataStore(realm).startSession();
                        try {
                           log(String.format("        Starting Transaction for Change Set:%s on database", changeSetBean.getName(), realm), emitter);

                           ds.startTransaction();
                           changeSetBean.execute(ds, mongoClient, emitter);
                           updateChangeLog(ds, realm, changeSetBean, emitter);
                           ds.commitTransaction();
                           Log.infof("        Committed Transaction for Change Set:%s", changeSetBean.getName());
                           emitter.emit(String.format("        Commited Transaction for Change Set:%s", changeSetBean.getName()));
                        } catch (Throwable e) {
                           emitter.fail(e);
                           e.printStackTrace();
                           ds.abortTransaction();
                           throw new RuntimeException(e);
                        } finally {
                           ds.close();
                        }
                     }
                  } else {
                     log(String.format("Ignoring Change Set:%s because it is not applicable to realm:%s", changeSetBean.getName(), realm), emitter);
                  }
               } else {
                  Log.infof("Change Set:%s has already been executed on database %s", changeSetBean.getName(), realm);
               }
               });
               log(String.format("-- Lock Released --"), emitter);
               log("-- All Change Sets executed --", emitter);
               log(String.format("-------------- Migration Completed for: %s--------------", realm), emitter);
            });
         } else {
            log(String.format("-- No pending change sets found for realm: %s", realm), emitter);
         }

         // The target version describes the migration catalog this realm has fully
         // processed, not only the last change set that mutated it. A change set may
         // intentionally apply to a subset of realms (for example, a system-catalog
         // migration). Tenant realms still need to advance past that version after
         // the change set has been considered and skipped as non-applicable; leaving
         // them at the prior version makes the request filter reject a clean database
         // forever. This point is reached only after every applicable change set has
         // completed successfully, so a failed migration can never stamp the target.
         Datastore datastore = morphiaDataStoreWrapper.getDataStore(realm);
         Optional<DatabaseVersion> current = getCurrentDatabaseVersion(datastore);
         Semver target = Semver.parse(targetDatabaseVersion);
         if (current.isEmpty() || current.get().getCurrentSemVersion().compareTo(target) < 0) {
            saveDatabaseVersion(datastore, targetDatabaseVersion);
            log(String.format("Advanced database version for realm %s to %s", realm, targetDatabaseVersion), emitter);
         }
   }



    private static @NotNull ChangeSetRecord newChangeSetRecord(String realm, ChangeSetBean changeSetBean) {
        ChangeSetRecord record = new ChangeSetRecord();
        record.setRealm(realm);
        record.setRefName(changeSetBean.getName());
        record.setAuthor(changeSetBean.getAuthor());
        record.setChangeSetName(changeSetBean.getName());
        record.setDescription(changeSetBean.getDescription());
        record.setPriority(changeSetBean.getPriority());
        record.setDbFromVersion(changeSetBean.getDbFromVersion());
        record.setDbFromVersionInt(changeSetBean.getDbFromVersionInt());
        record.setDbToVersion(changeSetBean.getDbToVersion());
        record.setDbToVersionInt(changeSetBean.getDbToVersionInt());
        record.setChangeSetVersion(changeSetBean.getChangeSetVersion());
        record.setLastExecutedDate(new Date());
        record.setScope(changeSetBean.getScope());
        record.setSuccessful(true);
        return record;
    }

   private void requireRealmOwnedByThisMigrationService(String realm, String operation) {
      if (!seedSystemOnly || isSystemRealm(realm) || isExplicitlyConfiguredMigrationRealm(realm)) {
         return;
      }
      throw new IllegalStateException(String.format(
         "Refusing to %s for realm %s because quantum.realm.seed-system-only=true and this service only owns system realm %s. "
            + "Set quantum.migration.apply.realms to explicitly opt in an additional managed realm.",
         operation,
         realm,
         systemRealm
      ));
   }

   private boolean isSystemRealm(String realm) {
      return Objects.equals(realm, systemRealm);
   }

   private boolean isExplicitlyConfiguredMigrationRealm(String realm) {
      if (startupRealmsCsv.isEmpty()) {
         return false;
      }
      String csv = startupRealmsCsv.get().trim();
      if (csv.isEmpty() || csv.equalsIgnoreCase("none")) {
         return false;
      }
      for (String part : csv.split(",")) {
         if (Objects.equals(part.trim(), realm)) {
            return true;
         }
      }
      return false;
   }

}
