package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.ValidationException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
public abstract class StaticDynamicList<T> extends BaseModel {

    @ConfigProperty(name="quantum.staticDynamicList.check-ids", defaultValue= "false")
    boolean checkIds;

    public enum Mode {
        STATIC,
        DYNAMIC
    }

    private String filterString;

    private Mode mode;

    private List<ObjectId> staticIds;

    public void setStaticIds(List<ObjectId> staticIds) {
        if (this.filterString != null ) {
            throw new ValidationException("Cannot set staticIds and filterString together. Choose either static or dynamic mode. StaticIds: " + staticIds + ", filterString: " + filterString);
        }
        this.mode = Mode.STATIC;

        if (checkIds) {
            for (ObjectId id : staticIds) {
                if (!ObjectId.isValid(id.toString())) {
                    throw new ValidationException("Invalid ObjectId: " + id);
                }
            }
        }

        this.staticIds = staticIds;
    }



    public void setFilterString(String filterString) {
        if (this.staticIds != null && !this.staticIds.isEmpty()) {
            throw new ValidationException("Cannot set staticIds and filterString together. Choose either static or dynamic mode. StaticIds: " + staticIds + ", filterString: " + filterString);
        }
        this.mode = Mode.DYNAMIC;
        this.filterString = filterString;
    }

    @JsonIgnore
    public boolean isStatic() {
        return mode == Mode.STATIC;
    }

    @JsonIgnore
    public boolean isDynamic() {
        return mode == Mode.DYNAMIC;
    }
}
