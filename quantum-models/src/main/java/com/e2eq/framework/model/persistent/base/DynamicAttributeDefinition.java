package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
@EqualsAndHashCode
@RegisterForReflection
@Entity
public class DynamicAttributeDefinition {
    @NonNull
    @NotNull
    protected String name;
    @NonNull
    @NotNull
    protected DynamicAttributeType type;
    @Builder.Default
    boolean required=false;
    @Builder.Default
    boolean inheritable=false;
    @Builder.Default
    boolean hidden=false;
    @Builder.Default
    boolean caseSensitive=false;

    protected List<SelectableValue> selectValues;

}
