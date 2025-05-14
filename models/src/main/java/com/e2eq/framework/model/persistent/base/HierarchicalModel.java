package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
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
public abstract class HierarchicalModel<T extends HierarchicalModel,
        O extends UnversionedBaseModel,
        L extends StaticDynamicList<O>> extends BaseModel {

    L staticDynamicList;

    @Schema(implementation = String.class, description = "collection of child HierarchicalModel ids")
    protected List<ObjectId> descendants;

    @Schema(implementation = HierarchicalModel.class, description = "this is calculated and not saved to the database and there for  should be read only")
    @JsonManagedReference
    @EqualsAndHashCode.Exclude
    protected List<T> children;

    @Schema(implementation = HierarchicalModel.class, description = "The parent of the HierarchicalModel, null if it is a root node")
    @JsonBackReference
    @EqualsAndHashCode.Exclude
    protected T parent;

    @PrePersist
    void beforeSave() {
        if (children != null) {
            Log.warnf("WARNING: Children set and will be ignored used descendants if your intentions are to add children, collection contains %d items", children.size());
            this.children = null; // or cache it somewhere else
        }
    }

}
