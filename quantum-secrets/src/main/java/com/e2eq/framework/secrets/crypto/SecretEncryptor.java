package com.e2eq.framework.secrets.crypto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AES-256-GCM encryption/decryption service with key-rotation support.
 *
 * <p>Key Encryption Keys (KEKs) are read from MicroProfile Config:
 * <ul>
 *   <li>{@code quantum.secrets.kek.active-version} — the key version used for new encryptions (default 1)</li>
 *   <li>{@code quantum.secrets.kek.v1}, {@code quantum.secrets.kek.v2}, ... — Base64-encoded 256-bit keys</li>
 * </ul>
 */
@ApplicationScoped
@RegisterForReflection
public class SecretEncryptor {

    private static final Logger LOG = Logger.getLogger(SecretEncryptor.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    @ConfigProperty(name = "quantum.secrets.kek.active-version", defaultValue = "1")
    int activeKeyVersion;

    @ConfigProperty(name = "quantum.secrets.kek.v1")
    Optional<String> kekV1;

    @ConfigProperty(name = "quantum.secrets.kek.v2")
    Optional<String> kekV2;

    @ConfigProperty(name = "quantum.secrets.kek.v3")
    Optional<String> kekV3;

    @ConfigProperty(name = "quantum.secrets.kek.v4")
    Optional<String> kekV4;

    @ConfigProperty(name = "quantum.secrets.kek.v5")
    Optional<String> kekV5;

    private final Map<Integer, SecretKey> keys = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private boolean keysAvailable = false;

    @PostConstruct
    void init() {
        registerKey(1, kekV1);
        registerKey(2, kekV2);
        registerKey(3, kekV3);
        registerKey(4, kekV4);
        registerKey(5, kekV5);

        if (keys.isEmpty()) {
            LOG.warn("No KEKs configured — encryption/decryption will be unavailable until "
                    + "quantum.secrets.kek.v<N> properties are set in application.properties.");
            keysAvailable = false;
            return;
        }

        if (!keys.containsKey(activeKeyVersion)) {
            LOG.warnf("Active KEK version %d is not configured. Available versions: %s. "
                    + "Encryption/decryption will be unavailable until quantum.secrets.kek.v%d is set.",
                    activeKeyVersion, keys.keySet(), activeKeyVersion);
            keysAvailable = false;
            return;
        }

        keysAvailable = true;
        LOG.infof("SecretEncryptor initialised — %d key(s) loaded, active version = %d",
                keys.size(), activeKeyVersion);
    }

    /**
     * Returns whether encryption keys are properly configured and available.
     *
     * @return {@code true} if KEKs are loaded and the active version is present
     */
    public boolean isKeysAvailable() {
        return keysAvailable;
    }

    /**
     * Returns the currently active key version used for new encryptions.
     */
    public int getActiveKeyVersion() {
        return activeKeyVersion;
    }

    /**
     * Encrypts plaintext using AES-256-GCM with the active key version.
     *
     * @param plaintext the value to encrypt
     * @return an {@link EncryptedValue} containing Base64-encoded ciphertext, IV, and key version
     */
    public EncryptedValue encrypt(String plaintext) {
        if (!keysAvailable) {
            throw new SecretEncryptionException(
                    "No KEK configured. Set quantum.secrets.kek.v" + activeKeyVersion
                    + " in application.properties.");
        }
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext must not be null or empty");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(activeKeyVersion), gcmSpec);

            byte[] ciphertextBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return EncryptedValue.builder()
                    .ciphertext(Base64.getEncoder().encodeToString(ciphertextBytes))
                    .iv(Base64.getEncoder().encodeToString(iv))
                    .keyVersion(activeKeyVersion)
                    .build();
        } catch (Exception e) {
            throw new SecretEncryptionException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a previously encrypted value.
     *
     * @param ciphertext  Base64-encoded ciphertext
     * @param iv          Base64-encoded initialization vector
     * @param keyVersion  the KEK version that was used to encrypt
     * @return the decrypted plaintext
     */
    public String decrypt(String ciphertext, String iv, int keyVersion) {
        if (!keysAvailable) {
            throw new SecretEncryptionException(
                    "No KEK configured. Set quantum.secrets.kek.v" + activeKeyVersion
                    + " in application.properties.");
        }
        if (ciphertext == null || iv == null) {
            throw new IllegalArgumentException("Ciphertext and IV must not be null");
        }
        try {
            byte[] ivBytes = Base64.getDecoder().decode(iv);
            byte[] ciphertextBytes = Base64.getDecoder().decode(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(keyVersion), gcmSpec);

            byte[] plaintextBytes = cipher.doFinal(ciphertextBytes);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecretEncryptionException("Decryption failed for key version " + keyVersion, e);
        }
    }

    /**
     * Re-encrypts a value from one key version to another.
     * Decrypts with {@code fromVersion}, then encrypts with {@code toVersion}.
     *
     * @param ciphertext   Base64-encoded ciphertext
     * @param iv           Base64-encoded initialization vector
     * @param fromVersion  the current key version
     * @param toVersion    the target key version
     * @return a new {@link EncryptedValue} encrypted under {@code toVersion}
     */
    public EncryptedValue reEncrypt(String ciphertext, String iv, int fromVersion, int toVersion) {
        String plaintext = decrypt(ciphertext, iv, fromVersion);
        // Temporarily override active version to encrypt with the target version
        int originalActive = this.activeKeyVersion;
        try {
            this.activeKeyVersion = toVersion;
            return encrypt(plaintext);
        } finally {
            this.activeKeyVersion = originalActive;
        }
    }

    // ---- internal helpers ----

    private SecretKey resolveKey(int version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new SecretEncryptionException("No KEK configured for version " + version);
        }
        return key;
    }

    private void registerKey(int version, Optional<String> base64Key) {
        base64Key.ifPresent(encoded -> {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "KEK v" + version + " must be exactly 256 bits (32 bytes), got " + decoded.length);
            }
            keys.put(version, new SecretKeySpec(decoded, ALGORITHM));
        });
    }

    /**
     * Runtime exception for encryption/decryption failures.
     */
    public static class SecretEncryptionException extends RuntimeException {
        public SecretEncryptionException(String message) {
            super(message);
        }

        public SecretEncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
