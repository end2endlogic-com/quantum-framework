package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.apache.commons.text.WordUtils;
import org.bson.types.ObjectId;

import java.util.*;

import static dev.morphia.utils.IndexType.DESC;

@Indexes({
            @Index(fields={@Field( value="refName", type=DESC),
                    @Field(value="dataDomain.orgRefName", type=DESC),
                    @Field(value="dataDomain.tenantId", type=DESC),
                    @Field(value="dataDomain.ownerId", type=DESC)},
                   options=@IndexOptions(unique=true)
                  )
})
@RegisterForReflection
@EqualsAndHashCode
public abstract @Data @NoArgsConstructor class BaseModel {

    @Id
    @JsonSerialize(using = ObjectIdJsonSerializer.class)
    protected ObjectId id;

    /**
     A consistent reference identifier, that will not change semantically if deleted, and re-inserted.  Typically,
     The ObjectId is / can be auto-assigned and is more akin to a record Id, this can make references that use the "Id"
     break if the object is deleted and re-inserted.  The refId is used as a "referenceId" and should be used vs. the Id.
     Systems like MongoDB however use the class name and the ID field for references, and Morphia like wise leverages this
     concept and uses the "id".  We may need to fork Morphia or create our own equivalent to handle these references if its
     truly important.  Like wise the use of an annotation to define the fields that should be used to build the refId may also
     be easier for the developer to use, with the default being that the refId will be the same value as the ID.
     */
    @JsonProperty(required = true)
    @NotNull
    @NonNull
    protected String refName;

    /**
     This string to use to display on a User Interface.  Human friendly identifier of this object
     */
    @NotNull
    @NonNull
    protected String displayName;

    /**
     The data domain this is a part of
     ( includes references to account, org, owner etc.
     */
    @Valid
    @NotNull
    @NonNull
    @JsonProperty(required = true)
    protected DataDomain dataDomain;

    /**
     The version of this object, many frameworks including Morphia have support for Optimistic locking and this field is used for this purpose
     */
    @Version
    protected Long version;

    /**
     The set of tags associated with this record.  A tag can be used for billing purposes, searching purposes or other purposes.
     */
    protected Set<Tag> tags = new HashSet<>();

    protected AuditInfo auditInfo = new AuditInfo();

    @Transient
    protected UIActionList actionList;

    // purposefully not included in equals or hash as well
    @Transient
    protected boolean checked=false;

    // purposefully not included in equals or hash as well

    @Transient
    List<String> defaultUIActions = Arrays.asList("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE");

    @PrePersist
    public void prePersist() {
        if (displayName == null) {
            displayName = refName;
        }
    }

    @Transient
    public List<String> defaultUIActions() {
        return defaultUIActions;
    }

    @Transient
    @JsonIgnore
    abstract public String bmFunctionalArea();

    /**
     Maps this class to a functional domain that will determine the set of
     uiactions based upon the current data model that can be applied to this
     object, and what of those actions the current user acting on this object
     can actually execute
     * @return either the classname by default or the overridden string to map to
     */
    @Transient
    @JsonIgnore
    abstract public String bmFunctionalDomain();

    public void setDataDomain (@Valid DataDomain dataDomain) {
        this.dataDomain = dataDomain;
    }


    public UIActionList calculateStateBasedUIActions () {

        UIActionList actionsMap = new UIActionList(defaultUIActions.size());

        for ( String defaultAction : defaultUIActions ) {
            UIAction action = new UIAction();
            action.setLabel(WordUtils.capitalize(defaultAction.toLowerCase().replace("_", " ")));
            action.setAction(defaultAction);
            actionsMap.add(action);
        }


        if (this.getId() != null ) {
            UIAction action = new UIAction();
            action.setLabel("Create");
            action.setAction("CREATE");
            actionsMap.remove(action);
        }

        return actionsMap;
    }


}
