package com.e2eq.framework.model.persistent.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@RegisterForReflection
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Data
@ToString
public class ReferenceEntry {
    @Schema(implementation = String.class, description = "MongoDB ObjectId as String")
    private ObjectId referencedId;
    private String type;
}