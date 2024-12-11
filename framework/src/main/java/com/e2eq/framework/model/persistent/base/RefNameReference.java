package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@EqualsAndHashCode
@RegisterForReflection
@Entity
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class RefNameReference {
    /**
     * The type of this reference and thus its corresponding collection
     */
    @NotNull (message = "type must not be null")
    protected String type;

    /**
     *  The refName that this reference points to
     */
    @NotNull(message = "refName must not be null")
    protected String refName;

    /**
     * The cached display of the referenced object
     */
    @NotNull(message = "refDisplayName must not be null")
    protected String refDisplayName;

}
