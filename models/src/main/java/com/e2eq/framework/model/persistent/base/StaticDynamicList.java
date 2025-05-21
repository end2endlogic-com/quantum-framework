package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.ValidationException;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Optional;

@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
public abstract class StaticDynamicList<T extends UnversionedBaseModel> extends BaseModel {

    @ConfigProperty(name="quantum.staticDynamicList.check-ids", defaultValue= "false")
    boolean checkIds;

    public enum Mode {
        STATIC,
        DYNAMIC
    }

    @Schema(implementation = String.class, description = "a filter string like the one used in the list api")
    private String filterString;

    @NotNull
    private Mode mode;

    public abstract List<T> getItems();
    public abstract void setItems(List<T> items);


    public void setFilterString(String filterString) {
        if (filterString != null && (this.getItems() != null && !this.getItems().isEmpty())) {
            throw new ValidationException("Cannot set filterString when the list of items is not empty, implying a static list clear the list first");
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
