package com.e2eq.framework.model.persistent.base;


import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import lombok.experimental.SuperBuilder;
import org.apache.commons.text.WordUtils;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.*;

import static dev.morphia.mapping.IndexType.DESC;

@Entity
@RegisterForReflection
@EqualsAndHashCode
@SuperBuilder
@Data
@NoArgsConstructor
public abstract  class BaseModel extends UnversionedBaseModel{
    /**
     The version of this object, many frameworks including Morphia have support for Optimistic locking and this field is used for this purpose
     */
    @Version
    protected Long version;

}
