package com.e2eq.framework.model.persistent.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;
import org.bson.types.ObjectId;

@RegisterForReflection
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Data
@ToString
public class ReferenceEntry {
    private ObjectId referencedId;
    private String type; // For example, the class name of the referencing entity
}