package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import net.bytebuddy.pool.TypePool;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import java.util.Map;

@Data
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@SuperBuilder
@Entity
@RegisterForReflection
public class EntityReference {
    /**
     * Optional but can be useful if you want to track this exact reference vs. the refName
     */
    @Schema(implementation = String.class, description = "object id of the entity being referenced")
    protected ObjectId entityId;

    @NotNull
    @NonNull
    protected String entityRefName;
    @NotNull
    @NonNull
    protected String entityDisplayName;

    @Deprecated
    protected Date dateTimeOfCopy; // the date and time the reference was created

    protected String entityType;

    @Deprecated
    protected Long version; // the version if its optimistically locked

    protected Map<String, Object> additionalFields;
}
