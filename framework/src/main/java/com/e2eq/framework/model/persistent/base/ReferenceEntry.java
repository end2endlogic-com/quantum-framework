package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.morphia.annotations.Property;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@RegisterForReflection
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Data
@ToString
public class ReferenceEntry {
    @NotNull
    @Schema(implementation = String.class, description = "MongoDB ObjectId as String")
    @JsonSerialize(using = ObjectIdJsonSerializer.class)
    private ObjectId referencedId;

    @NotNull
    private String type;

    /**
     * The refName is not used for the look up as it would require the datadomain as well to ensure uniquness, however it provides a better visual than just the id")
     * **/
    private String refName;

}