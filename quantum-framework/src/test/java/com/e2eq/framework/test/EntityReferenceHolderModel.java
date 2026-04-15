package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class EntityReferenceHolderModel extends BaseModel {

    @ReferenceTarget(target = ParentModel.class)
    protected EntityReference linkedParent;

    @Override
    public String bmFunctionalArea() {
        return "TEST";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
