package com.e2eq.framework.test;

import com.e2eq.framework.annotations.TrackReferences;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@Entity
public class TestChildModel extends BaseModel {

    protected String testField;

    @Reference(ignoreMissing = true)
    @TrackReferences
    TestParentModel parent;


    @Override
    public String bmFunctionalArea() {
        return "test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "references";
    }
}
