package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;
import org.semver4j.Semver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test resource that ensures MongoDB realm databases are initialized for tests.
 *
 * Responsibilities:
 * - For system and test realms, ensure DB exists and has a valid databaseVersion record
 *   with currentVersionString >= quantum.database.version. If not, drop DB and provision.
 * - Ensure system realm's catalog contains realm entries for system, test, and default realms.
 */
public class MongoDbInitResource implements QuarkusTestResourceLifecycleManager {

    private MongoClient mongoClient;

    @Override
    public Map<String, String> start() {
        try {
            String conn = ConfigProvider.getConfig().getValue("quarkus.mongodb.connection-string", String.class);
            this.mongoClient = MongoClients.create(conn);

            String requiredVersionStr = ConfigProvider.getConfig().getValue("quantum.database.version", String.class);
            Semver required = Semver.parse(requiredVersionStr);

            String systemRealm = getCfg("quantum.realmConfig.systemRealm", "system-com");
            String testRealm = getCfg("quantum.realmConfig.testRealm", "test-system-com");
            String defaultRealm = getCfg("quantum.realmConfig.defaultRealm", "mycompanyxyz-com");

            // Ensure system and test DBs are at required version; if not, drop (BaseRepoTest will migrate)
            ensureRealmInitialized(systemRealm, required);
            ensureRealmInitialized(testRealm, required);

            // Ensure system catalog contains system, test, and default realms
            ensureRealmCatalogEntries(systemRealm, defaultRealm, testRealm);
        } catch (Throwable t) {
            Log.error("MongoDbInitResource failed during start()", t);
            throw new RuntimeException(t);
        }
        return new HashMap<>();
    }

    @Override
    public void stop() {
        if (mongoClient != null) {
            try { mongoClient.close(); } catch (Exception ignored) {}
        }
    }

    private void ensureRealmInitialized(String realmDbName, Semver required) {
        boolean needsProvision = false;
        Set<String> dbNames = new HashSet<>();
        for (String n : mongoClient.listDatabaseNames()) {
            dbNames.add(n);
        }
        if (!dbNames.contains(realmDbName)) {
            Log.infof("Realm database %s does not exist; will provision.", realmDbName);
            needsProvision = true;
        } else {
            // Check databaseVersion collection
            MongoDatabase db = mongoClient.getDatabase(realmDbName);
            boolean hasCollection;
            try {
                hasCollection = db.listCollectionNames().into(new java.util.ArrayList<>()).stream()
                        .anyMatch(n -> n.equalsIgnoreCase("databaseVersion"));
            } catch (Exception e) {
                hasCollection = false;
            }
            if (!hasCollection) {
                Log.warnf("databaseVersion collection missing in %s; dropping and provisioning.", realmDbName);
                dropDatabaseQuietly(realmDbName);
                needsProvision = true;
            } else {
                // Read highest version
                Document latest = db.getCollection("databaseVersion")
                        .find()
                        .sort(new Document("currentVersionInt", -1))
                        .limit(1)
                        .first();
                if (latest == null) {
                    Log.warnf("No databaseVersion record found in %s; dropping and provisioning.", realmDbName);
                    dropDatabaseQuietly(realmDbName);
                    needsProvision = true;
                } else {
                    String cv = latest.getString("currentVersionString");
                    if (cv == null || cv.isBlank()) {
                        Log.warnf("databaseVersion record missing currentVersionString in %s; dropping and provisioning.", realmDbName);
                        dropDatabaseQuietly(realmDbName);
                        needsProvision = true;
                    } else {
                        Semver found = Semver.parse(cv);
                        if (found.compareTo(required) < 0) {
                            Log.warnf("database %s at %s < required %s; dropping and provisioning.", realmDbName, found, required);
                            dropDatabaseQuietly(realmDbName);
                            needsProvision = true;
                        }
                    }
                }
            }
        }

        if (needsProvision) {
            Log.infof("Realm database %s will be provisioned/migrated by test setup.", realmDbName);
        } else {
            Log.infof("Realm database %s already initialized and up-to-date.", realmDbName);
        }
    }

    private void dropDatabaseQuietly(String dbName) {
        try {
            mongoClient.getDatabase(dbName).drop();
        } catch (Exception e) {
            Log.warnf(e, "Failed to drop database %s (may not exist)", dbName);
        }
    }

    private void ensureRealmCatalogEntries(String systemRealm, String defaultRealm, String testRealm) throws JsonProcessingException {
        // Insert minimal realm documents into the system catalog if missing.
        MongoDatabase sysDb = mongoClient.getDatabase(systemRealm);

        String systemTenantId = getCfg("quantum.realmConfig.systemTenantId", "system.com");
        String systemOrgRefName = getCfg("quantum.realmConfig.systemOrgRefName", "system.com");
        String systemAccount = getCfg("quantum.realmConfig.systemAccountNumber", "0000000000");
        String systemAdmin = getCfg("quantum.realmConfig.systemUserId", "system@system.com");
        upsertRealm(sysDb, systemRealm, emailDomainFromTenantId(systemTenantId), systemOrgRefName, systemAccount, systemTenantId, systemAdmin);

        String testTenantId = getCfg("quantum.realmConfig.testTenantId", "test-system.com");
        String testOrgRefName = getCfg("quantum.realmConfig.testOrgRefName", "test-system.com");
        String testAccount = getCfg("quantum.realmConfig.testAccountNumber", "0000000000");
        String testAdmin = getCfg("quantum.realmConfig.testUserId", "test@test-system.com");
        upsertRealm(sysDb, testRealm, emailDomainFromTenantId(testTenantId), testOrgRefName, testAccount, testTenantId, testAdmin);

        String defTenantId = getCfg("quantum.realmConfig.defaultTenantId", "mycompanyxyz.com");
        String defOrgRefName = getCfg("quantum.realmConfig.defaultOrgRefName", "mycompanyxyz.com");
        String defAccount = getCfg("quantum.realmConfig.defaultAccountNumber", "9999999999");
        String defAdmin = getCfg("quantum.realmConfig.defaultUserId", "test@mycompanyxyz.com");
        upsertRealm(sysDb, defaultRealm, emailDomainFromTenantId(defTenantId), defOrgRefName, defAccount, defTenantId, defAdmin);

        // Ensure a test admin credential + user profile exists in the TEST realm, so SeedStartupRunner can resolve context
        ensureAdminUserExists(testRealm,
                getCfg("quantum.realmConfig.testTenantId", "test-quantum.com"),
                getCfg("quantum.realmConfig.testOrgRefName", "test-system.com"),
                getCfg("quantum.realmConfig.testAccountNumber", "0000000000"));
    }

    private void ensureAdminUserExists(String realmDbName, String tenantId, String orgRefName, String accountNum) throws JsonProcessingException {
        String adminUserId = "admin@" + tenantId;
        MongoDatabase testDb = mongoClient.getDatabase(realmDbName);
        // Collections
        var credColl = testDb.getCollection("credentialUserIdPassword");
        var upColl = testDb.getCollection("userProfile");

        // Check if credential exists
        Document cred = credColl.find(new Document("userId", adminUserId)).first();
        String credRefName = "cred-" + adminUserId;
        if (cred == null) {
            Document domainContext = new Document()
                    .append("tenantId", tenantId)
                    .append("orgRefName", orgRefName)
                    .append("defaultRealm", realmDbName)
                    .append("accountId", accountNum);
            cred = new Document()
                    .append("_t", "CredentialUserIdPassword")
                    .append("refName", credRefName)
                    .append("displayName", adminUserId)
                    .append("userId", adminUserId)
                    .append("subject", "sub-" + adminUserId)
                    .append("domainContext", domainContext)
                    .append("roles", java.util.List.of("ADMIN"))
                    .append("lastUpdate", new java.util.Date());
            credColl.insertOne(cred);
            Log.infof("Inserted test admin credential for %s in realm %s", adminUserId, realmDbName);
        }

        // Check if user profile exists
        Document up = upColl.find(new Document("email", adminUserId)).first();
        if (up == null) {
            Document entityRef = new Document()
                    .append("entityRefName", credRefName)
                    .append("entityType", "CredentialUserIdPassword")
                    .append("entityDisplayName", adminUserId)
                    .append("realm", realmDbName);
            Document dataDomain = new Document()
                    .append("orgRefName", orgRefName)
                    .append("accountNum", accountNum)
                    .append("tenantId", tenantId)
                    .append("dataSegment", 0)
                    .append("ownerId", adminUserId);

            up = new Document()
                    .append("_t", "UserProfile")
                    .append("refName", adminUserId)
                    .append("displayName", adminUserId)
                    .append("credentialUserIdPasswordRef", entityRef)
                    .append("email", adminUserId)
                    .append("userId", adminUserId)
                    .append("dataDomain", dataDomain);
            upColl.insertOne(up);
            Log.infof("Inserted test admin user profile for %s in realm %s", adminUserId, realmDbName);
        }
    }

    private void upsertRealm(MongoDatabase systemDb, String realmName, String emailDomain, String orgRefName, String accountId, String tenantId, String adminUserId) throws JsonProcessingException {
        Document coll = new Document();
        var realms = systemDb.getCollection("realm");
        Document existing = realms.find(new Document("databaseName", realmName)).first();
        DomainContext domainContext = DomainContext.builder()
                    .tenantId(tenantId)
                    .orgRefName(orgRefName)
                    .defaultRealm(realmName)
                    .accountId(accountId)
                    .build();
        Realm realm = Realm.builder()
                    .refName(realmName)
                    .displayName(realmName)
                    .emailDomain(emailDomain)
                    .databaseName(realmName)
                    .domainContext(domainContext)
                    .build();

        DataDomain dataDomain = DataDomain.builder()
                                   .dataSegment(0)
                                   .tenantId(tenantId)
                                   .orgRefName(orgRefName)
                                   .accountNum(accountId)
                                   .ownerId(adminUserId)
                                   .build();
        realm.setDataDomain(dataDomain);
        /*
        RealmRepo repo  = Arc.container().instance(RealmRepo.class).get();
       SecurityUtils securityUtils  = Arc.container().instance(SecurityUtils.class).get();

        SecurityCallScope.runWithContexts(securityUtils.getSystemPrincipalContext(), securityUtils.getSystemSecurityResourceContext(),
           () -> {
            repo.save(realm);
           }
        ); */


        ObjectMapper objectMapper = new ObjectMapper();
        Document parsed = Document.parse(objectMapper.writeValueAsString(realm));
        parsed.remove("id");

        Document desired = new Document();
        desired.append("_t","Realm");

        parsed.entrySet().forEach(e -> {
           if (e.getValue() != null)
              desired.append(e.getKey(), e.getValue());
        });

        if (existing == null) {
            realms.insertOne(desired);
            Log.infof("Inserted realm catalog entry for %s in system DB %s", realmName, systemDb.getName());
        } else {
           realms.updateOne(new Document("_id", existing.get("_id")), new Document("$set", desired));
           Log.infof("Updated realm catalog entry for %s in system DB %s", realmName, systemDb.getName());
        }
    }

    private String getCfg(String name, String defVal) {
        return ConfigProvider.getConfig().getOptionalValue(name, String.class).orElse(defVal);
    }

    private String emailDomainFromTenantId(String tenantId) {
        if (tenantId == null) return null;
        return tenantId.contains("-") ? tenantId.replace("-", "@") : tenantId;
    }
}
