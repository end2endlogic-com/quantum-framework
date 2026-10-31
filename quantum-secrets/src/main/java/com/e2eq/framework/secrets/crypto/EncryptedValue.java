package com.e2eq.framework.secrets.crypto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holds the result of an AES-256-GCM encryption operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class EncryptedValue {

    /** Base64-encoded ciphertext (includes GCM auth tag). */
    private String ciphertext;

    /** Base64-encoded 12-byte initialization vector. */
    private String iv;

    /** The KEK version that was used for encryption. */
    private int keyVersion;
}
