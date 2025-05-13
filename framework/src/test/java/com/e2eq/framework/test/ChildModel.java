package com.e2eq.framework.test;

import com.e2eq.framework.annotations.TrackReferences;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@Entity
public class ChildModel extends BaseModel {

    protected String testField;

    @Reference(ignoreMissing = true)
    @TrackReferences
    ParentModel parent;


    @Override
    public String bmFunctionalArea() {
        return "test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "references";
    }
}
