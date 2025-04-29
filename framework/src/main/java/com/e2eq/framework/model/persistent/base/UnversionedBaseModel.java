package com.e2eq.framework.model.persistent.base;


import com.e2eq.framework.model.persistent.morphia.interceptors.PersistenceAuditEventInterceptor;
import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.apache.commons.text.WordUtils;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static dev.morphia.mapping.IndexType.DESC;

@Entity
//@EntityListeners({ReferenceInterceptor.class, ValidationInterceptor.class, AuditInterceptor.class})
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
@SuperBuilder
@Data
@NoArgsConstructor
@EntityListeners(PersistenceAuditEventInterceptor.class)
public abstract  class UnversionedBaseModel {

    @Id
    @Schema(implementation = String.class, description = "MongoDB ObjectId as String")
    protected ObjectId id;

    /**
     A consistent reference identifier, that will not change semantically if deleted, and re-inserted.  Typically,
     The ObjectId is / can be auto-assigned and is more akin to a record Id, this can make references that use the "Id"
     break if the object is deleted and re-inserted.  The refId is used as a "referenceId" and should be used vs. the Id.
     Systems like MongoDB however use the class name and the ID field for references, and Morphia like wise leverages this
     concept and uses the "id".  We may need to fork Morphia or create our own equivalent to handle these references if its
     truly important.  Like wise the use of an annotation to define the fields that should be used to build the refId may also
     be easier for the developer to use, with the default being that the refId will be the same value as the ID.

     The id will default to the objectId if it is not set
     */
    @Size(min=3, message = "ref name must have a min size of 3 characters" )
    protected String refName;

    /**
     This string to use to display on a User Interface.  Human friendly identifier of this object
     */
    protected String displayName;

    /**
     The data domain this is a part of
      includes references to account, org, owner etc.
     */
    @Valid
    protected DataDomain dataDomain;

    protected ActiveStatus activeStatus;

    /**
     The set of tags associated with this record.  A tag can be used for billing purposes, searching purposes or other purposes.
     */
    protected String[] tags;
    protected Set<Tag> advancedTags;

    protected AuditInfo auditInfo;

    protected Set<ReferenceEntry> references;

    protected List<PersistentEvent> persistentEvents;

    @Transient
    protected UIActionList actionList;

    // purposefully not included in equals or hash as well
    @Transient
    @Builder.Default
    protected boolean skipValidation=false;

    // purposefully not included in equals or hash as well
    @Transient
    @Builder.Default
    List<String> defaultUIActions = Arrays.asList("CREATE", "UPDATE", "VIEW", "DELETE", "ARCHIVE");



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

    public EntityReference createEntityReference () {
        return EntityReference.builder()
                .entityId(this.getId())
                .entityType(this.getClass().getSimpleName())
                .entityRefName(this.getRefName())
                .entityDisplayName(this.getDisplayName())
                .build();
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
