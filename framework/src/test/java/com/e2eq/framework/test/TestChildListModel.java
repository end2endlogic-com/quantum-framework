package com.e2eq.framework.test;

import com.e2eq.framework.annotations.TrackReferences;
import com.e2eq.framework.model.persistent.base.BaseModel;
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
public class TestChildListModel extends BaseModel {

    @Reference(idOnly = true)
    @TrackReferences
    List<TestParentModel> parents;


    @Override
    public String bmFunctionalArea() {
        return "QUANTUM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
