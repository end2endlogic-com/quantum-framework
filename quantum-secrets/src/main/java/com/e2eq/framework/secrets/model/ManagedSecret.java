package com.e2eq.framework.secrets.model;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.FullBaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity("managedSecret")
@RegisterForReflection
@FunctionalMapping(area = "SECURITY", domain = "SECRET")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@ToString(callSuper = true)
public class ManagedSecret extends FullBaseModel {

    private String secretType;

    private String description;

    private String providerType;

    private boolean realmDefault;

    /**
     * AES-256-GCM encrypted ciphertext (Base64-encoded).
     * Encrypted using the KEK identified by {@link #keyVersion}.
     */
    private String valueEncrypted;

    /**
     * Base64-encoded 12-byte initialization vector used for GCM encryption.
     */
    private String iv;

    /**
     * The KEK version that was used to encrypt {@link #valueEncrypted}.
     * Enables key rotation — old secrets can be re-encrypted to a newer key.
     */
    private int keyVersion;

    public boolean isConfigured() {
        return valueEncrypted != null && !valueEncrypted.isBlank();
    }
}
