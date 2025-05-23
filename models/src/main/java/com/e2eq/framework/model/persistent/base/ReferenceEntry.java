package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    private ObjectId referencedId;

    /** the type that this reference is ie. the collection it is associated with */
    @NotNull
    private String type;


    /**
     * The refName is not used for the look up as it would require the datadomain as well to ensure uniquness, however it provides a better visual than just the id")
     * **/
    private String refName;

}