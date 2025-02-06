package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;
import java.util.Map;

@Data
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@SuperBuilder
public class EntityReference {
    @JsonSerialize(using = ObjectIdJsonSerializer.class)
    @Schema(implementation = String.class, description = "object id of the entity being referenced")
    @NotNull
    protected ObjectId entityId;
    @NotNull
    protected String entityRefName;
    @NotNull
    protected String entityDisplayName;
    @NotNull
    protected Date dateTimeOfCopy; // the date and time the reference was created

    protected String entityType;

    protected Long version; // the version if its optimistically locked

    protected Map<String, Object> additionalFields;
}
