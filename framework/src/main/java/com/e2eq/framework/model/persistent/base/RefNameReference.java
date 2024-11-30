package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode
@RegisterForReflection
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class RefNameReference {
    protected String refName;
    protected String refDisplayName;
}
