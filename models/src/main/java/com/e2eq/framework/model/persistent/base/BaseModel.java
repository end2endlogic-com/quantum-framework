package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Version;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@RegisterForReflection
@EqualsAndHashCode( callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
public abstract  class BaseModel extends UnversionedBaseModel{
    /**
     The version of this object, many frameworks including Morphia have support for Optimistic locking and this field is used for this purpose
     */
    @Version
    protected Long version;

}
