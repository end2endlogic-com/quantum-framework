package com.e2eq.framework.secrets.crypto;

import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.secrets.model.ManagedSecret;
import com.e2eq.framework.secrets.model.ManagedSecretRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service to migrate existing plaintext secrets to AES-256-GCM encryption.
 *
 * <p>Existing {@link ManagedSecret} documents stored before encryption was introduced
 * have {@code iv == null} and {@code keyVersion == 0}. Their {@code valueEncrypted}
 * field contains the raw plaintext. This service detects those documents, encrypts
 * the plaintext with the active KEK, and updates the document in-place.</p>
 */
@ApplicationScoped
public class SecretsMigrationService {

    private static final Logger LOG = Logger.getLogger(SecretsMigrationService.class);

    @Inject
    ManagedSecretRepo secretRepo;

    @Inject
    SecretEncryptor encryptor;

    @Inject
    RealmRepo realmRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    /**
     * Migrate plaintext secrets across ALL realms.
     *
     * @return per-realm migration results
     */
    public Map<String, RealmMigrationResult> migrateAllRealms() {
        String systemRealm = envConfigUtils.getSystemRealm();
        List<Realm> allRealms = realmRepo.getAllListWithIgnoreRules(systemRealm);

        LOG.infof("Starting secrets migration across %d realm(s)", allRealms.size());

        Map<String, RealmMigrationResult> results = new LinkedHashMap<>();
        for (Realm realm : allRealms) {
            String realmId = realm.getDatabaseName();
            try {
                RealmMigrationResult result = migrateRealm(realmId);
                results.put(realmId, result);
                LOG.infof("Realm '%s': migrated=%d, skipped=%d, failed=%d",
                        realmId, result.migrated, result.skipped, result.failed);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to migrate realm '%s'", realmId);
                results.put(realmId, new RealmMigrationResult(0, 0, 0, e.getMessage()));
            }
        }

        return results;
    }

    /**
     * Migrate plaintext secrets for a single realm.
     *
     * @param realmId the realm (database name) to migrate
     * @return migration result counts
     */
    public RealmMigrationResult migrateRealm(String realmId) {
        List<ManagedSecret> secrets = secretRepo.findAll(realmId, null);

        int migrated = 0;
        int skipped = 0;
        int failed = 0;

        for (ManagedSecret secret : secrets) {
            // Detect plaintext: iv is null means this was never encrypted
            if (secret.getIv() != null && !secret.getIv().isEmpty()) {
                // Already encrypted
                skipped++;
                continue;
            }

            if (secret.getValueEncrypted() == null || secret.getValueEncrypted().isEmpty()) {
                // No value to migrate
                skipped++;
                continue;
            }

            try {
                // The current valueEncrypted IS the plaintext — encrypt it
                String plaintext = secret.getValueEncrypted();
                EncryptedValue encrypted = encryptor.encrypt(plaintext);

                secret.setValueEncrypted(encrypted.getCiphertext());
                secret.setIv(encrypted.getIv());
                secret.setKeyVersion(encrypted.getKeyVersion());

                secretRepo.save(realmId, secret);
                migrated++;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to migrate secret '%s' in realm '%s'",
                        secret.getRefName(), realmId);
                failed++;
            }
        }

        return new RealmMigrationResult(migrated, skipped, failed, null);
    }

    /**
     * Generates a cryptographically secure 256-bit AES key, Base64-encoded.
     * This is a utility for operators to generate KEK values for configuration.
     *
     * @return Base64-encoded 256-bit key
     */
    public static String generateKek() {
        byte[] key = new byte[32]; // 256 bits
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Result of migrating secrets in a single realm.
     */
    public static class RealmMigrationResult {
        public final int migrated;
        public final int skipped;
        public final int failed;
        public final String error;

        public RealmMigrationResult(int migrated, int skipped, int failed, String error) {
            this.migrated = migrated;
            this.skipped = skipped;
            this.failed = failed;
            this.error = error;
        }
    }
}
