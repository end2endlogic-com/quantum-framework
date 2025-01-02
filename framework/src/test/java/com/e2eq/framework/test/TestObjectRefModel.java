package com.e2eq.framework.test;

import com.e2eq.framework.annotations.ObjectReference;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.persistent.ObjectReferenceListener;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.EntityListeners;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Entity (useDiscriminator = false)
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@EntityListeners(ObjectReferenceListener.class)
public class TestObjectRefModel extends BaseModel {

    protected String testField;

    @ObjectReference
    protected TestParentModel parent;

    @Override
    public String bmFunctionalArea() {
        return "QUANTUM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TEST";
    }
}
