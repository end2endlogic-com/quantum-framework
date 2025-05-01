package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;

@Data
@EqualsAndHashCode
@Entity
@ToString
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class StringNameValue {
    private String name;
    private String value;
}
