package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Property;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * Entity representing a seed registry entry tracking which seed datasets have been applied.
 * Stored in the _seed_registry collection.
 */
@Entity("_seed_registry")
@RegisterForReflection
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Data
public class SeedRegistryEntry extends UnversionedBaseModel {

    @Property("seedPack")
    @Indexed
    private String seedPack;

    @Property("version")
    @Indexed
    private String version;

    @Property("dataset")
    @Indexed
    private String dataset;

    @Property("checksum")
    private String checksum;

    private Integer records;

    @Property("appliedAt")
    private Instant appliedAt;

    @Property("appliedToRealm")
    @Indexed
    private String appliedToRealm;

    @Override
    public String bmFunctionalArea() {
        return "SEED";
    }

    @Override
    public String bmFunctionalDomain() {
        return "SEED_REGISTRY";
    }
}

