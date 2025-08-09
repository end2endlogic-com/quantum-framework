package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.PrePersist;
import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

// T - This HierarchicalModel type
// O - The baseModel that is used in at each level
// L - the static dynamic list of type O
@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
@ToString
public abstract class HierarchicalModel<T extends HierarchicalModel<T,O,L>,
        O extends UnversionedBaseModel,
        L extends StaticDynamicList<O>> extends BaseModel {

    L staticDynamicList;

    @Schema(implementation = String.class, description = "collection of child HierarchicalModel ids")
    protected List<ObjectId> descendants;

    @Schema(implementation = HierarchicalModel.class, description = "this is calculated and not saved to the database and therefore should be read only")
    @JsonIgnore
    protected List<T> children;

    @Schema(implementation = HierarchicalModel.class, description = "The parent of the HierarchicalModel, null if it is a root node")
    protected EntityReference parent;

    @PrePersist
    void beforeSave() {
        if (children != null) {
            Log.warnf("WARNING: Children set on HierarchicalModel id=%s; will be ignored. Use 'descendants' to add children. Collection contains %d items", getId(), children.size());
            this.children = null; // or cache it somewhere else
        }
    }

}
