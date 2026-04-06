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
     * Placeholder field name retained for forward compatibility with a stronger
     * encryption-at-rest implementation.
     */
    private String valueEncrypted;

    public boolean isConfigured() {
        return valueEncrypted != null && !valueEncrypted.isBlank();
    }
}
